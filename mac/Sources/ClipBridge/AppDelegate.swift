import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, ServerOwner {
    private var config = Config.loadOrCreate()
    private var server: Server!
    private var watcher: PasteboardWatcher!
    private var adb: AdbManager?   // only when config.usbSetup is on
    private var statusItem: NSStatusItem!
    private var signalSources: [DispatchSourceSignal] = []

    // Sync state. Main thread only.
    private var currentId = Clip.newId()
    // Whatever is on the clipboard at launch counts as "fresh as of now" but is never pushed
    // (currentClip is nil), so restarting the agent can neither send nor receive stale contents.
    private var currentTime: Int64 = Clip.now()
    private var currentClip: Clip?
    private var peers: [Peer] = []
    private var adbStatus = AdbManager.Status()
    private var lastSync: (date: Date, what: String)?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // --takeover: started by the launchd-run copy just before it removes itself as a login item.
        // --retry-login-item=N: started by a copy whose login item would not launch (repairLoginItem).
        let takeover = CommandLine.arguments.contains("--takeover")
        let retryPrefix = "--retry-login-item="
        let retry = CommandLine.arguments.first { $0.hasPrefix(retryPrefix) }.flatMap { Int($0.dropFirst(retryPrefix.count)) }
        let managed = LoginItem.isAgentProcess && !takeover && retry == nil
        let others = Instances.others()
        if !others.isEmpty {
            if managed || takeover || retry != nil {
                // A hand-over: the other copy is about to step aside. If it does not, leave it be.
                if !Instances.waitForExit(others, timeout: 8) { exit(0) }
            } else {
                Instances.askRunningCopyToShow() // already running: show its menu instead
                exit(0)
            }
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
        Log.info("ClipBridge \(version) starting" + (managed ? " (login item)" : ""))
        if !managed && !takeover { adoptLoginItem(attempt: retry ?? 0) } // may hand over to the launchd-run copy and exit
        if #available(macOS 15.4, *) {
            Log.info("pasteboard access: \(NSPasteboard.general.accessBehavior.rawValue) (0 default, 1 ask, 2 always allow, 3 always deny)")
        }
        installSignalHandlers()
        setUpStatusItem()
        cleanInbox(keeping: nil)

        server = Server(port: config.port, psk: config.keyData, maxBytes: config.maxBytes)
        server.owner = self
        server.setCurrent(id: currentId, time: currentTime)
        server.start()

        watcher = PasteboardWatcher { [weak self] in self?.pasteboardChanged() }
        watcher.start()

        if config.usbSetup == true {
            Log.info("USB setup is enabled (config.json: usbSetup)")
            let manager = AdbManager(config: config) { [weak self] status in
                self?.adbStatus = status
                self?.refreshIcon()
            }
            manager.start()
            adb = manager
        }

        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.adb?.recheck()
        }
        DistributedNotificationCenter.default().addObserver(forName: Instances.showRequest, object: nil, queue: .main) { [weak self] _ in
            self?.statusItem.button?.performClick(nil)
        }

        // A brand-new install has nothing to sync with yet: go straight to pairing.
        if config.welcomed == false {
            config.welcomed = true
            config.save()
            pairPhone()
        }
    }

    // MARK: start at login

    /// Runs in a copy the user opened by hand. Makes sure the login item exists and, when launchd
    /// then runs its own copy (the one that restarts after a crash), steps aside for it.
    /// Whether this copy looks after its own login item. A copy installed by install.sh does not:
    /// that script gives locally signed builds a plain LaunchAgent (see LoginItem.isTeamSigned for
    /// why). Every other copy does, including a locally signed one that was simply downloaded and
    /// dragged into Applications, which would otherwise never start at login.
    private var selfRegisters: Bool {
        guard LoginItem.hasBundledAgent, config.selfRegister != false else { return false }
        return LoginItem.isTeamSigned || config.selfRegister == true || !LoginItem.hasDeveloperLaunchAgent
    }

    /// `attempt` is 0 for a copy the user opened, and N in the Nth fresh copy started by a repair.
    private func adoptLoginItem(attempt: Int) {
        guard selfRegisters else { return }
        guard LoginItem.isInstalled else {
            askToInstallFirst()
            return
        }
        guard config.startAtLogin != false else { return }
        if attempt > 0 { _ = Instances.waitForOther(timeout: 2) } // let macOS finish the removal
        if LoginItem.isEnabled {
            LoginItem.kickstart()
        } else if LoginItem.needsApproval {
            Log.info("login item: \(LoginItem.statusText)")
            return
        } else if !LoginItem.register() {
            return
        }
        if Instances.waitForOther(timeout: attempt > 0 ? 5 : 3) { handOver() }
        guard LoginItem.isEnabled else { return }
        repairLoginItem(attempt: attempt + 1)
    }

    /// Registered, yet launchd's copy does not come up: macOS is refusing to launch it, which it
    /// does when the app on disk is not the build the login item was registered by. This copy keeps
    /// syncing. The one sequence seen to clear it (macOS 27): leave it registered for half a minute
    /// or more, have a separate process remove it, then have a freshly started copy register it.
    /// After two tries the login item is removed, so launchd stops retrying a launch that fails.
    private func repairLoginItem(attempt: Int) {
        guard attempt <= 2 else {
            LoginItem.unregister()
            Log.info("login item could not be started; Start at Login is off until it is ticked again")
            return
        }
        Log.info("login item did not start; carrying on, and restarting in 40 s to register it again")
        DispatchQueue.main.asyncAfter(deadline: .now() + 40) { [weak self] in
            guard let self, self.config.startAtLogin != false, LoginItem.isEnabled else { return }
            if !Instances.others().isEmpty { self.handOver() }
            LoginItem.unregisterInSeparateProcess()
            Instances.launchCopy(arguments: ["--retry-login-item=\(attempt)"])
            Log.flush()
            exit(0)
        }
    }

    private func handOver() -> Never {
        Log.info("handing over to the login item")
        Log.flush()
        exit(0)
    }

    private func askToInstallFirst() {
        Log.info("not in an Applications folder (\(Bundle.main.bundlePath)); login item not registered")
        let alert = NSAlert()
        alert.messageText = "Move ClipBridge to your Applications folder"
        alert.informativeText = "ClipBridge runs in the background and starts when you log in, so it needs a "
            + "permanent home. Drag it into Applications, then open it from there."
        alert.addButton(withTitle: "Quit")
        alert.addButton(withTitle: "Run Anyway")
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertFirstButtonReturn {
            Log.flush()
            exit(0)
        }
    }

    @objc private func toggleStartAtLogin() {
        if LoginItem.isEnabled {
            config.startAtLogin = false
            config.save()
            // Removing the login item stops the launchd-run copy. If that is this process, start a
            // plain copy first so syncing carries on.
            if LoginItem.isAgentProcess { Instances.launchCopy(arguments: ["--takeover"]) }
            LoginItem.unregister()
            return
        }
        config.startAtLogin = true
        config.save()
        guard LoginItem.isInstalled else {
            askToInstallFirst()
            return
        }
        if LoginItem.register() {
            if !LoginItem.isAgentProcess, Instances.waitForOther(timeout: 3) { handOver() }
        } else {
            LoginItem.openSettings() // switched off there earlier; only the user can switch it back
        }
    }

    /// `kill <pid>` and logout should stop us for good; only a crash makes launchd restart us.
    private func installSignalHandlers() {
        for sig in [SIGTERM, SIGINT] {
            signal(sig, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: sig, queue: .global(qos: .userInitiated))
            src.setEventHandler { exit(0) }
            src.resume()
            signalSources.append(src)
        }
    }

    // MARK: Mac -> phone

    /// File-system work for copied files happens here, never on the main thread: macOS may hold a
    /// file access behind a permission prompt for as long as the user takes to answer it.
    private let fileQueue = DispatchQueue(label: "dev.clipbridge.files")
    private var copyGeneration = 0
    private var fileAccessDenied = false

    private func pasteboardChanged() {
        guard !config.paused else { return }
        copyGeneration += 1
        let generation = copyGeneration
        guard let snapshot = PasteboardReader.snapshot(maxBytes: config.maxBytes) else { return }
        switch snapshot {
        case .items(let items, let sensitive):
            publishLocal(items, sensitive: sensitive)
        case .files(let urls, let sensitive):
            let maxBytes = config.maxBytes
            fileQueue.async { [weak self] in
                let result = PasteboardReader.fileItems(urls, maxBytes: maxBytes)
                DispatchQueue.main.async {
                    guard let self, generation == self.copyGeneration else { return } // a newer copy won
                    switch result {
                    case .items(let items):
                        self.fileAccessDenied = false
                        self.publishLocal(items, sensitive: sensitive)
                    case .denied:
                        self.fileAccessDenied = true
                        Log.info("macOS did not allow access to the copied file(s); not synced")
                    case .skipped(let why):
                        Log.info(why)
                    }
                }
            }
        }
    }

    private func publishLocal(_ items: [ClipItem], sensitive: Bool) {
        // Strictly later than anything seen so far, even if the phone's clock runs ahead of ours.
        let clip = Clip(id: Clip.newId(), time: max(Clip.now(), currentTime + 1), sensitive: sensitive, items: items)
        setCurrent(clip)
        Log.info("local copy: \(clip.summary)")
        if !peers.isEmpty, !peers.contains(where: { $0.interactive }) {
            Log.info("phone screen is off; it will get this when it wakes")
        }
        for peer in peers { catchUp(peer) }
    }

    private func setCurrent(_ clip: Clip) {
        currentClip = clip
        currentId = clip.id
        currentTime = clip.time
        server.setCurrent(id: clip.id, time: clip.time)
    }

    /// Sends the current clip to a phone if that phone does not have it yet.
    /// A phone with its screen off is skipped; it gets the latest clip the moment it wakes,
    /// so copying on the Mac never wakes a sleeping phone.
    private func catchUp(_ peer: Peer) {
        guard !config.paused, let clip = currentClip else { return }
        guard peer.deliveredClipId != clip.id, clip.time > peer.clipTime, peer.interactive else { return }
        server.send(clip, to: peer.sessionId)
    }

    // MARK: ServerOwner

    func serverPeersChanged(_ peers: [Peer]) {
        let before = self.peers.first { $0.deliveredClipId == currentId } != nil
        let known = Set(self.peers.map { $0.sessionId })
        if let arrived = peers.first(where: { !known.contains($0.sessionId) }) {
            pairingWindow?.showPaired(with: arrived.name) // takes the key off the screen once it has worked
        }
        self.peers = peers
        if !before, let clip = currentClip, peers.contains(where: { $0.deliveredClipId == clip.id }), !isRemote(clip) {
            lastSync = (Date(), "sent " + describe(clip))
        }
        refreshIcon()
    }

    func serverWantsCatchUp(_ peer: Peer) {
        catchUp(peer)
    }

    func serverReceived(_ clip: Clip, from peer: Peer) {
        guard !config.paused else { return }
        copyGeneration += 1 // a clip from the phone supersedes a file copy still waiting on a prompt
        guard PasteboardWriter.write(clip) != nil else {
            Log.info("could not place clip from \(peer.name) on the pasteboard: \(clip.summary)")
            return
        }
        watcher.ignoreCurrent()
        remoteIds.insert(clip.id)
        setCurrent(clip)
        cleanInbox(keeping: clip.id)
        lastSync = (Date(), "received " + describe(clip))
        Log.info("applied from \(peer.name): \(clip.summary)")
        // Other connected phones get it too.
        for other in peers where other.sessionId != peer.sessionId { catchUp(other) }
    }

    private var remoteIds: Set<String> = []
    private func isRemote(_ clip: Clip) -> Bool { remoteIds.contains(clip.id) }

    private func describe(_ clip: Clip) -> String {
        guard let first = clip.items.first else { return "nothing" }
        if first.kind == "text" { return "text" }
        if clip.items.count > 1 { return "\(clip.items.count) files" }
        return first.mime.hasPrefix("image/") ? "an image" : "a file"
    }

    /// Files received from the phone live in the inbox only while they are on the clipboard.
    private func cleanInbox(keeping id: String?) {
        let fm = FileManager.default
        try? fm.createDirectory(at: Config.inbox, withIntermediateDirectories: true)
        guard let entries = try? fm.contentsOfDirectory(at: Config.inbox, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        let sorted = entries.sorted {
            let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return a > b
        }
        // Keep the newest few so a paste that is still in flight never loses its file.
        for url in sorted.dropFirst(3) where url.lastPathComponent != id {
            try? fm.removeItem(at: url)
        }
    }

    // MARK: menu bar

    private func setUpStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        refreshIcon()
    }

    private func refreshIcon() {
        guard let button = statusItem?.button else { return }
        let connected = !peers.isEmpty
        let name = config.paused ? "pause.circle" : (connected ? "doc.on.clipboard.fill" : "doc.on.clipboard")
        if let image = NSImage(systemSymbolName: name, accessibilityDescription: "ClipBridge") {
            image.isTemplate = true
            button.image = image
            button.title = ""
        } else {
            button.title = "CB"
        }
        button.appearsDisabled = !connected && !config.paused
        button.toolTip = headline()
    }

    private func headline() -> String {
        if config.paused { return "ClipBridge is paused" }
        if peers.isEmpty { return "ClipBridge: phone not connected" }
        return "ClipBridge: connected to " + peers.map { $0.name }.joined(separator: ", ")
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        func info(_ title: String) {
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.isEnabled = false
            menu.addItem(item)
        }

        if peers.isEmpty {
            info("Phone not connected")
            if adb == nil {
                info("Same Wi\u{2011}Fi? Open ClipBridge on the phone to reconnect")
                info("New phone? Choose Pair a Phone\u{2026}")
            } else if !adbStatus.adbFound {
                info("adb not found — install Android platform-tools")
            } else if adbStatus.needsAuthorization {
                info("Allow USB debugging on the phone")
            } else if adbStatus.devices.isEmpty {
                info("USB setup is on: plug the phone in")
            } else {
                for d in adbStatus.devices { info(d) }
                info("Waiting for the phone to connect…")
            }
        } else {
            for p in peers {
                info("Connected to \(p.name)" + (p.interactive ? "" : " (screen off)"))
            }
        }
        if let lastSync {
            let f = RelativeDateTimeFormatter()
            f.unitsStyle = .full
            let ago = Date().timeIntervalSince(lastSync.date) < 2 ? "just now" : f.localizedString(for: lastSync.date, relativeTo: Date())
            info("Last: \(lastSync.what), \(ago)")
        }

        // macOS can restrict apps that read the clipboard without a paste keystroke.
        if #available(macOS 15.4, *) {
            let behavior = NSPasteboard.general.accessBehavior
            if behavior == .ask || behavior == .alwaysDeny {
                menu.addItem(.separator())
                info(behavior == .alwaysDeny ? "macOS is blocking clipboard access" : "macOS asks before each clipboard read")
                let fix = NSMenuItem(title: "Allow in Privacy Settings…", action: #selector(openPasteSettings), keyEquivalent: "")
                fix.target = self
                menu.addItem(fix)
            }
        }

        if fileAccessDenied {
            menu.addItem(.separator())
            info("macOS blocked access to a copied file")
            let fix = NSMenuItem(title: "Allow in Files and Folders\u{2026}", action: #selector(openFilesSettings), keyEquivalent: "")
            fix.target = self
            menu.addItem(fix)
        }

        menu.addItem(.separator())
        let pause = NSMenuItem(title: "Pause Syncing", action: #selector(togglePause), keyEquivalent: "")
        pause.target = self
        pause.state = config.paused ? .on : .off
        menu.addItem(pause)

        let pair = NSMenuItem(title: "Pair a Phone…", action: #selector(pairPhone), keyEquivalent: "")
        pair.target = self
        menu.addItem(pair)

        if selfRegisters {
            let login = NSMenuItem(title: "Start at Login", action: #selector(toggleStartAtLogin), keyEquivalent: "")
            login.target = self
            login.state = LoginItem.isEnabled ? .on : .off
            menu.addItem(login)
        }

        let log = NSMenuItem(title: "Show Log", action: #selector(showLog), keyEquivalent: "")
        log.target = self
        menu.addItem(log)

        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit ClipBridge", action: #selector(quit), keyEquivalent: "q")
        quit.target = self
        menu.addItem(quit)
    }

    @objc private func togglePause() {
        config.paused.toggle()
        config.save()
        if !config.paused { watcher.ignoreCurrent() } // nothing copied while paused is ever sent
        Log.info(config.paused ? "paused" : "resumed")
        refreshIcon()
    }

    /// Privacy & Security › Paste from Other Apps: set ClipBridge to "Allow".
    /// Privacy & Security › Files and Folders: lets ClipBridge read files copied from protected folders.
    @objc private func openFilesSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_FilesAndFolders") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func openPasteSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Pasteboard") {
            NSWorkspace.shared.open(url)
        }
    }

    private var pairingWindow: PairingWindowController?

    @objc private func pairPhone() {
        pairingWindow?.close()
        let controller = PairingWindowController(config: config)
        pairingWindow = controller
        controller.show()
    }

    @objc private func showLog() {
        NSWorkspace.shared.activateFileViewerSelecting([Log.url])
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}
