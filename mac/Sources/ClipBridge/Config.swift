import Foundation

/// Persistent settings, including the pairing key shared with the phone.
/// Stored at ~/Library/Application Support/ClipBridge/config.json (mode 0600).
struct Config: Codable {
    var key: String          // base64, 32 random bytes
    var port: UInt16
    var maxBytes: Int64
    var paused: Bool
    /// Developer convenience, off unless set to true in config.json: when a phone is visible to adb,
    /// install the bundled phone app, push the pairing config and start the helper over the cable.
    /// Normal setup needs none of this — the phone pairs by QR code and starts its own helper.
    var usbSetup: Bool?
    /// false once the user unticks "Start at Login"; anything else means register the login item.
    var startAtLogin: Bool?
    /// false on a brand-new install until the pairing window has been shown once.
    var welcomed: Bool?
    /// Developer switch: let a locally signed (ad-hoc) build register itself as a login item, which
    /// normally only Developer ID builds do. See LoginItem.isTeamSigned for why it is off.
    var selfRegister: Bool?

    static let dir: URL = {
        let d = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/ClipBridge")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    static let file = dir.appendingPathComponent("config.json")
    static let inbox = dir.appendingPathComponent("Inbox")

    var keyData: Data { Data(base64Encoded: key) ?? Data() }

    static func loadOrCreate() -> Config {
        if let data = try? Data(contentsOf: file), let c = try? JSONDecoder().decode(Config.self, from: data), c.keyData.count == 32 {
            return c
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let c = Config(key: Data(bytes).base64EncodedString(), port: 47823, maxBytes: 100 * 1024 * 1024, paused: false,
                       usbSetup: nil, startAtLogin: nil, welcomed: false, selfRegister: nil)
        c.save()
        return c
    }

    func save() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? enc.encode(self) else { return }
        try? data.write(to: Config.file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Config.file.path)
    }
}
