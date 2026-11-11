import Foundation

struct ClipItem {
    enum Body {
        case data(Data)
        case file(URL)
    }

    let kind: String // "text" | "html" | "file"
    let mime: String
    let name: String
    let size: Int64
    let body: Body
}

struct Clip {
    let id: String
    let time: Int64 // ms since epoch, on the device where the copy happened
    let sensitive: Bool
    let items: [ClipItem]

    var total: Int64 { items.reduce(0) { $0 + $1.size } }

    /// Sizes and types only — safe to log.
    var summary: String {
        items.map { "\($0.kind)(\($0.mime),\($0.size))" }.joined(separator: " ") + (sensitive ? " [sensitive]" : "")
    }

    func headerJSON() -> Data {
        let obj: [String: Any] = [
            "id": id,
            "time": time,
            "sensitive": sensitive,
            "items": items.map { ["kind": $0.kind, "mime": $0.mime, "name": $0.name, "size": $0.size] as [String: Any] },
        ]
        return (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
    }

    static func newId() -> String {
        (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Produces the frames for one clip lazily, so large files are streamed rather than loaded.
final class OutgoingClip {
    let clip: Clip
    private var stage = 0
    private var itemIndex = 0
    private var offset: Int64 = 0
    private var handle: FileHandle?

    init(_ clip: Clip) { self.clip = clip }

    var finished: Bool { stage == 3 }

    func nextFrame() throws -> (FrameType, Data)? {
        if stage == 0 {
            stage = 1
            return (.clip, clip.headerJSON())
        }
        if stage == 1 {
            while itemIndex < clip.items.count {
                let item = clip.items[itemIndex]
                let left = item.size - offset
                if left <= 0 {
                    itemIndex += 1
                    offset = 0
                    try? handle?.close()
                    handle = nil
                    continue
                }
                let n = Int(min(Int64(WireCrypto.chunk), left))
                let chunk: Data
                switch item.body {
                case .data(let d):
                    let start = d.startIndex + Int(offset)
                    chunk = d.subdata(in: start..<(start + n))
                case .file(let url):
                    if handle == nil { handle = try FileHandle(forReadingFrom: url) }
                    guard let got = try handle?.read(upToCount: n), !got.isEmpty else {
                        throw CocoaError(.fileReadUnknown)
                    }
                    chunk = got
                }
                offset += Int64(chunk.count)
                return (.data, chunk)
            }
            stage = 2
        }
        if stage == 2 {
            stage = 3
            let end = (try? JSONSerialization.data(withJSONObject: ["id": clip.id])) ?? Data()
            return (.end, end)
        }
        return nil
    }

    func cancel() {
        try? handle?.close()
        handle = nil
        stage = 3
    }
}

/// Reassembles a clip arriving from the phone. Text stays in memory; files go to the inbox folder.
final class IncomingClip {
    private let id: String
    private let time: Int64
    private let sensitive: Bool
    private var metas: [(kind: String, mime: String, name: String, size: Int64)] = []
    private var buffers: [Data] = []
    private var handles: [FileHandle?] = []
    private var urls: [URL?] = []
    private var remaining: [Int64] = []
    private var index = 0
    private let dir: URL

    init?(header: Data, inbox: URL, maxBytes: Int64) {
        guard let obj = try? JSONSerialization.jsonObject(with: header) as? [String: Any],
              let rawId = obj["id"] as? String,
              let items = obj["items"] as? [[String: Any]], !items.isEmpty, items.count <= 32
        else { return nil }
        let cleanId = rawId.filter { $0.isHexDigit }
        guard !cleanId.isEmpty, cleanId.count <= 32 else { return nil }
        id = cleanId
        time = (obj["time"] as? NSNumber)?.int64Value ?? Clip.now()
        sensitive = (obj["sensitive"] as? Bool) ?? false
        dir = inbox.appendingPathComponent(cleanId)
        var total: Int64 = 0
        for it in items {
            guard let kind = it["kind"] as? String, let size = (it["size"] as? NSNumber)?.int64Value, size >= 0 else { return nil }
            metas.append((kind, (it["mime"] as? String) ?? "application/octet-stream", (it["name"] as? String) ?? "", size))
            total += size
        }
        guard total <= maxBytes else {
            Log.info("incoming clip too large (\(total) bytes); ignoring")
            return nil
        }
        for (i, m) in metas.enumerated() {
            remaining.append(m.size)
            buffers.append(Data())
            if m.kind == "file" {
                var name = (m.name as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "_")
                if name.isEmpty || name == "." || name == ".." { name = "clipboard" }
                let sub = dir.appendingPathComponent(String(i))
                let url = sub.appendingPathComponent(name)
                do {
                    try FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
                    FileManager.default.createFile(atPath: url.path, contents: nil)
                    handles.append(try FileHandle(forWritingTo: url))
                    urls.append(url)
                } catch {
                    return nil
                }
            } else {
                handles.append(nil)
                urls.append(nil)
            }
        }
    }

    func append(_ data: Data) throws {
        var rest = data
        while !rest.isEmpty {
            while index < remaining.count && remaining[index] == 0 { index += 1 }
            guard index < remaining.count else { throw WireError.badFrame }
            let n = Int(min(Int64(rest.count), remaining[index]))
            let part = Data(rest.prefix(n))
            if let h = handles[index] { try h.write(contentsOf: part) } else { buffers[index].append(part) }
            remaining[index] -= Int64(n)
            rest = Data(rest.dropFirst(n))
        }
    }

    /// Nil if the clip is incomplete.
    func finish() -> Clip? {
        for h in handles { try? h?.close() }
        guard remaining.allSatisfy({ $0 == 0 }) else {
            discard()
            return nil
        }
        var items: [ClipItem] = []
        for (i, m) in metas.enumerated() {
            if let url = urls[i] {
                items.append(ClipItem(kind: m.kind, mime: m.mime, name: url.lastPathComponent, size: m.size, body: .file(url)))
            } else {
                items.append(ClipItem(kind: m.kind, mime: m.mime, name: m.name, size: m.size, body: .data(buffers[i])))
            }
        }
        return Clip(id: id, time: time, sensitive: sensitive, items: items)
    }

    func discard() {
        for h in handles { try? h?.close() }
        try? FileManager.default.removeItem(at: dir)
    }
}
