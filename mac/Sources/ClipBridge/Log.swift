import Foundation

/// Appends to ~/Library/Logs/ClipBridge/clipbridge.log. Clipboard contents are never logged.
enum Log {
    static let url: URL = {
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/ClipBridge")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("clipbridge.log")
    }()

    private static let queue = DispatchQueue(label: "dev.clipbridge.log")
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    /// Wait for pending lines to reach the file. Call before exit().
    static func flush() {
        queue.sync {}
    }

    static func info(_ message: String) {
        let now = Date()
        queue.async {
            let line = "\(formatter.string(from: now)) \(message)\n"
            FileHandle.standardError.write(Data(line.utf8))
            if let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int, size > 1_000_000 {
                let old = url.appendingPathExtension("1")
                try? FileManager.default.removeItem(at: old)
                try? FileManager.default.moveItem(at: url, to: old)
            }
            if let h = try? FileHandle(forWritingTo: url) {
                defer { try? h.close() }
                _ = try? h.seekToEnd()
                try? h.write(contentsOf: Data(line.utf8))
            } else {
                try? Data(line.utf8).write(to: url)
            }
        }
    }
}
