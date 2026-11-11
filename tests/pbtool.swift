import AppKit
import CryptoKit

// Test helper: inspect or set the Mac pasteboard without ever printing its contents.
func sha(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined().prefix(16).description }
let pb = NSPasteboard.general
let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "info" {
case "info":
    for t in pb.types ?? [] {
        let d = pb.data(forType: t) ?? Data()
        print("type \(t.rawValue) bytes=\(d.count) sha=\(sha(d))")
    }
    if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] {
        for u in urls {
            let d = (try? Data(contentsOf: u)) ?? Data()
            print("file \(u.lastPathComponent) bytes=\(d.count) sha=\(sha(d))")
        }
    }
case "text":
    pb.clearContents(); pb.setString(args[2], forType: .string)
case "html":
    let item = NSPasteboardItem()
    item.setString(args[2], forType: .string); item.setString(args[3], forType: .html)
    pb.clearContents(); pb.writeObjects([item])
case "png":
    let d = try! Data(contentsOf: URL(fileURLWithPath: args[2]))
    pb.clearContents(); pb.setData(d, forType: .png)
case "tiff":
    let d = try! Data(contentsOf: URL(fileURLWithPath: args[2]))
    let rep = NSBitmapImageRep(data: d)!
    pb.clearContents(); pb.setData(rep.tiffRepresentation!, forType: .tiff)
case "files":
    let items = args.dropFirst(2).map { path -> NSPasteboardItem in
        let i = NSPasteboardItem(); i.setString(URL(fileURLWithPath: path).absoluteString, forType: .fileURL); return i
    }
    pb.clearContents(); pb.writeObjects(items)
    RunLoop.current.run(until: Date().addingTimeInterval(1.0)) // let the pasteboard server collect the items before we exit
case "concealed":
    let item = NSPasteboardItem()
    item.setString(args[2], forType: .string)
    item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
    pb.clearContents(); pb.writeObjects([item])
case "transient":
    let item = NSPasteboardItem()
    item.setString(args[2], forType: .string)
    item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
    pb.clearContents(); pb.writeObjects([item])
default: print("?")
}
