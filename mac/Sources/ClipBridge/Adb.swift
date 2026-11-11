import CryptoKit
import Foundation
import SystemConfiguration

/// Host names straight from System Configuration (Host.current() can block on DNS for many seconds).
enum SystemInfo {
    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    }

    /// e.g. "Someones-MacBook-Pro.local" — resolvable from the phone via mDNS.
    static var localHostName: String? {
        guard let n = SCDynamicStoreCopyLocalHostName(nil) as String?, !n.isEmpty else { return nil }
        return n + ".local"
    }
}

/// Keeps the phone-side helper alive. Whenever adb can see the phone (USB, or wireless debugging)
/// this installs/updates the companion app, pushes the pairing config, and starts the helper if it
/// is not already running. The helper then survives unplugging; only a phone reboot stops it.
final class AdbManager {
    struct Status {
        var adbFound = false
        var devices: [String] = []      // human-readable, one per phone
        var needsAuthorization = false
    }

    private static let remoteDir = "/data/local/tmp/clipbridge"
    private static let package = "dev.clipbridge"

    private let queue = DispatchQueue(label: "dev.clipbridge.adb")
    private let config: Config
    private let onStatus: (Status) -> Void
    private var adbPath: String?
    private var lastArmed: [String: Date] = [:]
    private var models: [String: String] = [:]
    private var lastState: [String: String] = [:]
    private var timer: DispatchSourceTimer?
    private lazy var apkURL: URL? = Bundle.main.url(forResource: "clipbridge", withExtension: "apk")
    private lazy var apkHash: String? = {
        guard let url = apkURL, let data = try? Data(contentsOf: url) else { return nil }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }()

    init(config: Config, onStatus: @escaping (Status) -> Void) {
        self.config = config
        self.onStatus = onStatus
    }

    func start() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.5, repeating: 4.0, leeway: .milliseconds(500))
        t.setEventHandler { [weak self] in self?.poll() }
        t.resume()
        timer = t
    }

    /// Forget what we armed, so the next poll re-checks every phone straight away.
    func recheck() {
        queue.async { self.lastArmed.removeAll() }
    }

    // MARK: polling

    private func findAdb() -> String? {
        let env = ProcessInfo.processInfo.environment
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        var candidates: [String] = []
        if let h = env["ANDROID_HOME"] { candidates.append(h + "/platform-tools/adb") }
        if let h = env["ANDROID_SDK_ROOT"] { candidates.append(h + "/platform-tools/adb") }
        candidates += [home + "/Library/Android/sdk/platform-tools/adb", "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private func poll() {
        var status = Status()
        if adbPath == nil { adbPath = findAdb() }
        guard adbPath != nil else {
            onStatusMain(status)
            return
        }
        status.adbFound = true
        guard let out = adb(["devices"], timeout: 10)?.out else {
            onStatusMain(status)
            return
        }
        var present: Set<String> = []
        for line in out.split(separator: "\n").dropFirst() {
            let parts = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
            guard parts.count >= 2 else { continue }
            let serial = String(parts[0])
            switch parts[1] {
            case "device":
                present.insert(serial)
                let state = ensureArmed(serial)
                if lastState[serial] != state {
                    lastState[serial] = state
                    Log.info("phone \(models[serial] ?? serial): \(state)")
                }
                status.devices.append("\(models[serial] ?? serial): \(state)")
            case "unauthorized":
                status.needsAuthorization = true
            default:
                break
            }
        }
        for serial in lastArmed.keys where !present.contains(serial) { lastArmed[serial] = nil }
        for serial in lastState.keys where !present.contains(serial) {
            Log.info("phone \(models[serial] ?? serial): no longer visible to adb")
            lastState[serial] = nil
        }
        onStatusMain(status)
    }

    private func onStatusMain(_ s: Status) {
        DispatchQueue.main.async { self.onStatus(s) }
    }

    // MARK: arming

    private func ensureArmed(_ serial: String) -> String {
        if let last = lastArmed[serial], Date().timeIntervalSince(last) < 60 { return "helper running" }
        let dir = AdbManager.remoteDir

        if models[serial] == nil, let m = shell(serial, "getprop ro.product.model")?.trimmed, !m.isEmpty {
            models[serial] = m
        }

        // 1. Companion app: install or update when the bundled APK differs from what we installed.
        let installed = (shell(serial, "pm path \(AdbManager.package)") ?? "").contains("package:")
        if let apk = apkURL, let hash = apkHash {
            let onPhone = shell(serial, "cat \(dir)/apk.sha 2>/dev/null")?.trimmed
            if !installed || onPhone != hash {
                Log.info("installing phone app on \(serial)")
                guard let r = adb(["-s", serial, "install", "-r", apk.path], timeout: 120), r.code == 0 else {
                    return "could not install the phone app"
                }
                _ = shell(serial, "mkdir -p \(dir) && echo \(hash) > \(dir)/apk.sha")
            }
        } else if !installed {
            return "phone app not installed"
        }

        // 2. Pairing key + where to find this Mac.
        let cfg = remoteConfig()
        let cfgHash = SHA256.hash(data: cfg).map { String(format: "%02x", $0) }.joined()
        let have = shell(serial, "sha256sum \(dir)/config.json 2>/dev/null")?.split(separator: " ").first.map(String.init)
        if have != cfgHash {
            let script = "mkdir -p \(dir) && chmod 700 \(dir) && cat > \(dir)/config.tmp && chmod 600 \(dir)/config.tmp && mv \(dir)/config.tmp \(dir)/config.json"
            guard let r = adb(["-s", serial, "shell", script], stdin: cfg, timeout: 15), r.code == 0 else {
                return "could not write config"
            }
        }

        // 3. A tunnel over the cable, so syncing works even without shared Wi-Fi.
        _ = adb(["-s", serial, "reverse", "tcp:\(config.port)", "tcp:\(config.port)"], timeout: 10)

        // 4. Start the helper unless the right version is already running.
        let want = apkHash ?? "dev"
        guard let r = adb(["-s", serial, "shell", "sh", "-s"], stdin: Data(armScript(want: want).utf8), timeout: 30) else {
            return "could not start helper"
        }
        let result = r.out.trimmed
        if result.hasSuffix("RUNNING") || result.hasSuffix("STARTED") {
            if result.hasSuffix("STARTED") { Log.info("started phone helper on \(models[serial] ?? serial)") }
            lastArmed[serial] = Date()
            return "helper running"
        }
        Log.info("arming \(serial) failed: \(result.suffix(200))")
        return "could not start helper"
    }

    private func remoteConfig() -> Data {
        var hosts: [String] = []
        if let local = SystemInfo.localHostName { hosts.append(local) }
        hosts += lanAddresses()
        let obj: [String: Any] = [
            "key": config.key,
            "port": Int(config.port),
            "hosts": hosts,
            "name": SystemInfo.computerName,
            "maxBytes": config.maxBytes,
        ]
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
    }

    private func lanAddresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return result }
        defer { freeifaddrs(ifaddr) }
        var p = ifaddr
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            guard let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(cur.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: cur.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result.sorted()
    }

    /// Runs on the phone. Idempotent: prints RUNNING if the right helper is already up.
    private func armScript(want: String) -> String {
        let d = AdbManager.remoteDir
        return """
        D=\(d)
        alive() { [ -n "$1" ] && [ -d "/proc/$1" ] && grep -q "$2" "/proc/$1/cmdline" 2>/dev/null; }
        PID=$(cat $D/pid 2>/dev/null)
        WP=$(cat $D/wrapper.pid 2>/dev/null)
        if alive "$PID" clipbridged && alive "$WP" clipbridge && [ "$(cat $D/armed 2>/dev/null)" = "\(want)" ]; then
          echo RUNNING
          exit 0
        fi
        alive "$WP" clipbridge && kill "$WP" 2>/dev/null
        alive "$PID" clipbridged && kill "$PID" 2>/dev/null
        sleep 0.5
        cat > $D/run.sh <<'EOS'
        D=\(d)
        echo $$ > $D/wrapper.pid
        while true; do
          APK=$(pm path \(AdbManager.package) 2>/dev/null | head -1 | cut -d: -f2)
          [ -z "$APK" ] && exit 0
          CLASSPATH=$APK app_process / --nice-name=clipbridged dev.clipbridge.daemon.Main $D
          [ $? -eq 0 ] && exit 0
          sleep 3
        done
        EOS
        chmod 700 $D/run.sh
        setsid nohup sh $D/run.sh </dev/null >/dev/null 2>&1 &
        echo "\(want)" > $D/armed
        echo STARTED
        """
    }

    // MARK: process helpers

    private func shell(_ serial: String, _ command: String) -> String? {
        adb(["-s", serial, "shell", command], timeout: 15)?.out
    }

    private func adb(_ args: [String], stdin: Data? = nil, timeout: TimeInterval) -> (code: Int32, out: String)? {
        guard let adbPath else { return nil }
        return run(adbPath, args, stdin: stdin, timeout: timeout)
    }

    private func run(_ path: String, _ args: [String], stdin: Data? = nil, timeout: TimeInterval) -> (code: Int32, out: String)? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let outPipe = Pipe()
        p.standardOutput = outPipe
        p.standardError = outPipe
        let inPipe = Pipe()
        p.standardInput = stdin != nil ? inPipe : FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        if let stdin {
            inPipe.fileHandleForWriting.write(stdin)
            try? inPipe.fileHandleForWriting.close()
        }
        let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: killer)
        let data = outPipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        killer.cancel()
        return (p.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
