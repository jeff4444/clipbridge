import AppKit
import CoreImage.CIFilterBuiltins
import Foundation

/// Builds the pairing payload the phone needs (the shared key and where to reach this Mac) and
/// renders it as a QR code. This is what replaces pushing config.json over a USB cable.
enum Pairing {
    /// The JSON the phone stores as its config.json: shared key, port, how to reach this Mac.
    static func configJSON(_ config: Config) -> String {
        let obj: [String: Any] = [
            "key": config.key,
            "port": Int(config.port),
            "hosts": hosts(),
            "name": SystemInfo.computerName,
            "maxBytes": config.maxBytes,
            "v": 1,
        ]
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Bonjour name first (survives IP changes), then current LAN IPv4 addresses.
    static func hosts() -> [String] {
        var result: [String] = []
        if let local = SystemInfo.localHostName { result.append(local) }
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
                let addr = String(cString: host)
                if !result.contains(addr) { result.append(addr) }
            }
        }
        return result
    }

    static func qrImage(from string: String, scale: CGFloat = 10) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

/// A small window showing the pairing QR and short instructions.
final class PairingWindowController: NSWindowController {
    private let config: Config

    init(config: Config) {
        self.config = config
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 460),
            styleMask: [.titled, .closable],
            backing: .buffered, defer: false)
        window.title = "Pair a Phone"
        window.center()
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.contentView = buildContent()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        window?.level = .floating
    }

    /// A phone has connected while the code was on screen: replace the code (it contains the key)
    /// with a confirmation, then close.
    func showPaired(with name: String) {
        guard let window, window.isVisible else { return }
        let container = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 460))

        let mark = NSImageView(frame: NSRect(x: 130, y: 250, width: 100, height: 100))
        mark.image = NSImage(systemSymbolName: "checkmark.circle.fill", accessibilityDescription: "Connected")
        mark.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 72, weight: .regular)
        mark.contentTintColor = .systemGreen
        container.addSubview(mark)

        let title = label("Connected to \(name)", size: 20, bold: true)
        title.frame = NSRect(x: 20, y: 190, width: 320, height: 28)
        title.alignment = .center
        container.addSubview(title)

        let hint = label("Copy on either device and paste on the other. ClipBridge stays in the menu bar.",
                         size: 13, bold: false)
        hint.frame = NSRect(x: 24, y: 120, width: 312, height: 52)
        hint.alignment = .center
        hint.textColor = .secondaryLabelColor
        container.addSubview(hint)

        window.contentView = container
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in self?.close() }
    }

    private func buildContent() -> NSView {
        let container = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 460))

        let title = label("Pair a Phone", size: 20, bold: true)
        title.frame = NSRect(x: 20, y: 410, width: 320, height: 28)
        title.alignment = .center
        container.addSubview(title)

        let qr = NSImageView(frame: NSRect(x: 60, y: 150, width: 240, height: 240))
        qr.image = Pairing.qrImage(from: Pairing.configJSON(config))
        qr.imageScaling = .scaleProportionallyUpOrDown
        qr.wantsLayer = true
        qr.layer?.backgroundColor = NSColor.white.cgColor
        container.addSubview(qr)

        let steps = label(
            "On your phone, open ClipBridge → Set up → “Scan the code on your Mac”, "
                + "then point the camera here.\n\nKeep this Mac and your phone on the same Wi‑Fi.",
            size: 13, bold: false)
        steps.frame = NSRect(x: 24, y: 40, width: 312, height: 96)
        steps.alignment = .center
        steps.textColor = .secondaryLabelColor
        container.addSubview(steps)

        return container
    }

    private func label(_ text: String, size: CGFloat, bold: Bool) -> NSTextField {
        let f = NSTextField(labelWithString: text)
        f.font = bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
        f.lineBreakMode = .byWordWrapping
        f.maximumNumberOfLines = 0
        return f
    }
}
