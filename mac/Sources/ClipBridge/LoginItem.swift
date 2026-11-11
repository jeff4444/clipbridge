import AppKit
import ServiceManagement

/// Start at login, registered by the app itself so a downloaded copy needs no install script.
///
/// The bundle carries a launchd agent (Contents/Library/LaunchAgents/<plistName>). Once it is
/// registered, macOS starts ClipBridge at login and restarts it after a crash, but leaves it stopped
/// after Quit. The agent sets CLIPBRIDGE_AGENT=1, so a process can tell it is the launchd-run copy.
enum LoginItem {
    static let label = "dev.clipbridge.mac.agent"
    static let plistName = label + ".plist"

    static var isAgentProcess: Bool {
        ProcessInfo.processInfo.environment["CLIPBRIDGE_AGENT"] == "1"
    }

    private static var service: SMAppService { SMAppService.agent(plistName: plistName) }

    static var isEnabled: Bool { service.status == .enabled }

    /// The user switched ClipBridge off under System Settings › Login Items. Only they can undo that.
    static var needsApproval: Bool { service.status == .requiresApproval }

    static var statusText: String {
        switch service.status {
        case .enabled: return "enabled"
        case .notRegistered: return "not registered"
        case .requiresApproval: return "turned off in System Settings"
        case .notFound: return "not found"
        @unknown default: return "unknown"
        }
    }

    /// Builds made without the agent file (plain `swift build`) simply skip all of this.
    static var hasBundledAgent: Bool {
        FileManager.default.fileExists(atPath: Bundle.main.bundlePath + "/Contents/Library/LaunchAgents/" + plistName)
    }

    /// Signed with a real certificate (Developer ID), as opposed to ad-hoc for local use.
    ///
    /// macOS ties a login item to the identity of the code that registered it. A team-signed app
    /// keeps its identity across versions. An ad-hoc build is a new identity every time it is
    /// rebuilt, and macOS then refuses to launch the login item ("launch constraint violation"), so
    /// local builds use a plain LaunchAgent written by install.sh instead of registering themselves.
    static let isTeamSigned: Bool = {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return false }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return false }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let dict = info as? [String: Any] else { return false }
        return (dict[kSecCodeInfoTeamIdentifier as String] as? String)?.isEmpty == false
    }()

    /// Is this copy somewhere it can stay? macOS runs an app opened straight from a disk image or
    /// from Downloads out of a temporary, randomised location, which is useless as a login item.
    static var isInstalled: Bool {
        let path = Bundle.main.bundlePath
        if path.contains("/AppTranslocation/") { return false }
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        return path.hasPrefix("/Applications/") || path.hasPrefix(home + "/Applications/")
    }

    @discardableResult
    static func register() -> Bool {
        do {
            try service.register()
        } catch {
            Log.info("login item: could not register (\(error.localizedDescription))")
        }
        Log.info("login item: \(statusText)")
        return isEnabled
    }

    /// Also stops the launchd-run copy if there is one.
    static func unregister() {
        let status = service.status
        guard status == .enabled || status == .requiresApproval else { return }
        do {
            try service.unregister()
            Log.info("login item: removed")
        } catch {
            Log.info("login item: could not remove (\(error.localizedDescription))")
        }
    }

    /// The same, done by a short-lived second process (this executable with a flag; see main.swift).
    static func unregisterInSeparateProcess() {
        guard let exe = Bundle.main.executablePath else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = ["--unregister-login-item"]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
            p.waitUntilExit()
        } catch {
            unregister()
        }
    }

    /// Ask launchd to start its copy now (it may be registered but stopped, after a Quit).
    /// Not waited for: launchctl can take several seconds when the job cannot be started.
    static func kickstart() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        p.arguments = ["kickstart", "gui/\(getuid())/\(label)"]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
    }

    static func openSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}

/// Only one ClipBridge runs at a time. These helpers find the other copies and pass the baton.
enum Instances {
    static let showRequest = Notification.Name("dev.clipbridge.mac.show")

    static func others() -> [pid_t] {
        guard let id = Bundle.main.bundleIdentifier else { return [] }
        let me = ProcessInfo.processInfo.processIdentifier
        return NSRunningApplication.runningApplications(withBundleIdentifier: id)
            .map { $0.processIdentifier }
            .filter { $0 > 0 && $0 != me }
    }

    /// True once every listed process has gone.
    static func waitForExit(_ pids: [pid_t], timeout: TimeInterval) -> Bool {
        wait(timeout) { pids.allSatisfy { kill($0, 0) != 0 && errno == ESRCH } }
    }

    /// True once another copy is running.
    static func waitForOther(timeout: TimeInterval) -> Bool {
        wait(timeout) { !others().isEmpty }
    }

    private static func wait(_ timeout: TimeInterval, until done: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if done() { return true }
            // Let the run loop turn so the list of running applications stays current.
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.1))
            usleep(50_000)
        }
        return done()
    }

    /// Opening ClipBridge while it is already running shows its menu instead of doing nothing.
    static func askRunningCopyToShow() {
        DistributedNotificationCenter.default().postNotificationName(showRequest, object: nil, userInfo: nil, deliverImmediately: true)
    }

    /// Start a separate copy of this app (not a child of this process) and wait until it is launched.
    static func launchCopy(arguments: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        p.arguments = ["-n", "-a", Bundle.main.bundlePath, "--args"] + arguments
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
            p.waitUntilExit()
        } catch {
            Log.info("could not start a new copy: \(error.localizedDescription)")
        }
    }
}
