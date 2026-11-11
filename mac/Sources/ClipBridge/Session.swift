import CryptoKit
import Foundation
import Network

protocol SessionDelegate: AnyObject {
    // All called on the network queue.
    func helloBody(for session: Session) -> Data
    func sessionReady(_ session: Session)
    func session(_ session: Session, received clip: Clip)
    func sessionBecameInteractive(_ session: Session)
    func sessionChanged(_ session: Session)
    func sessionClosed(_ session: Session)
}

/// One encrypted connection to a phone. Everything here runs on the network queue.
final class Session {
    let id = UUID()
    private let conn: NWConnection
    private let queue: DispatchQueue
    private let psk: Data
    private let inbox: URL
    private let maxBytes: Int64
    weak var delegate: SessionDelegate?

    private var keys: SessionKeys?
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private var closed = false

    private(set) var ready = false
    private(set) var peerName = "Android"
    private(set) var peerDevice = ""
    private(set) var peerClipTime: Int64 = 0
    private(set) var peerInteractive = true
    /// Id of the clip we know is on the phone's clipboard.
    private(set) var deliveredClipId = ""

    private var control: [(FrameType, Data)] = []
    private var active: OutgoingClip?
    private var sending = false
    private var incoming: IncomingClip?
    private var lastReceive = Date()

    /// While its screen is on the phone pings every 30 s, so a long silence means it has gone.
    /// (With the screen off it is silent by design, and we never probe it awake.)
    var looksDead: Bool { ready && peerInteractive && Date().timeIntervalSince(lastReceive) > 75 }

    init(conn: NWConnection, queue: DispatchQueue, psk: Data, inbox: URL, maxBytes: Int64) {
        self.conn = conn
        self.queue = queue
        self.psk = psk
        self.inbox = inbox
        self.maxBytes = maxBytes
    }

    var remoteDescription: String { "\(conn.endpoint)" }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.respondToHandshake()
            case .failed(let error): self?.close("connection failed: \(error)")
            default: break
            }
        }
        conn.start(queue: queue)
        // Anything that has not identified itself within a few seconds is not our phone.
        queue.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self, !self.ready else { return }
            self.close("no hello")
        }
    }

    func close(_ reason: String) {
        guard !closed else { return }
        closed = true
        incoming?.discard()
        incoming = nil
        active?.cancel()
        active = nil
        conn.cancel()
        if ready { Log.info("\(peerName) disconnected (\(reason))") }
        delegate?.sessionClosed(self)
    }

    /// Queue a clip for the phone. A newer clip replaces one that is still being sent.
    func send(_ clip: Clip) {
        guard ready, !closed else { return }
        if deliveredClipId == clip.id || active?.clip.id == clip.id { return }
        active?.cancel()
        active = OutgoingClip(clip)
        pump()
    }

    // MARK: handshake

    private func respondToHandshake() {
        receiveExactly(WireCrypto.handshakeLength) { [weak self] data in
            guard let self else { return }
            guard let theirs = WireCrypto.parseHandshake(data) else {
                self.close("not a ClipBridge client")
                return
            }
            let mine = WireCrypto.randomNonce()
            self.keys = WireCrypto.derive(psk: self.psk, initiatorNonce: theirs, responderNonce: mine, weAreInitiator: false)
            self.conn.send(content: WireCrypto.handshake(nonce: mine), completion: .contentProcessed { [weak self] error in
                guard let self else { return }
                if let error { self.close("handshake send: \(error)"); return }
                if let hello = self.delegate?.helloBody(for: self) { self.enqueue(.hello, hello) }
                self.readFrame()
            })
        }
    }

    // MARK: receiving

    private func receiveExactly(_ n: Int, _ then: @escaping (Data) -> Void) {
        conn.receive(minimumIncompleteLength: n, maximumLength: n) { [weak self] data, _, _, error in
            guard let self, !self.closed else { return }
            if let data, data.count == n {
                then(data)
            } else {
                self.close(error.map { "\($0)" } ?? "closed by phone")
            }
        }
    }

    private func readFrame() {
        receiveExactly(4) { [weak self] header in
            guard let self else { return }
            let b = [UInt8](header)
            let len = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard len >= 17, len <= WireCrypto.maxFrame else {
                self.close("bad frame length")
                return
            }
            self.receiveExactly(len) { [weak self] sealed in
                guard let self, let keys = self.keys else { return }
                do {
                    let (type, body) = try WireCrypto.open(sealed, key: keys.recv, counter: self.recvCounter)
                    self.recvCounter += 1
                    self.handle(type, body)
                } catch {
                    // Wrong pairing key, or not our phone at all.
                    self.close("could not decrypt (pairing key mismatch?)")
                    return
                }
                self.readFrame()
            }
        }
    }

    private func handle(_ rawType: UInt8, _ body: Data) {
        lastReceive = Date()
        guard let type = FrameType(rawValue: rawType) else { return }
        switch type {
        case .hello:
            guard let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return }
            peerName = (obj["name"] as? String) ?? "Android"
            peerDevice = (obj["device"] as? String) ?? ""
            deliveredClipId = (obj["clipId"] as? String) ?? ""
            peerClipTime = (obj["clipTime"] as? NSNumber)?.int64Value ?? 0
            peerInteractive = (obj["interactive"] as? Bool) ?? true
            ready = true
            Log.info("\(peerName) connected from \(remoteDescription)")
            delegate?.sessionReady(self)
        case .clip:
            incoming?.discard()
            incoming = IncomingClip(header: body, inbox: inbox, maxBytes: maxBytes)
        case .data:
            do { try incoming?.append(body) } catch {
                incoming?.discard()
                incoming = nil
            }
        case .end:
            guard let done = incoming else { return }
            incoming = nil
            guard let clip = done.finish() else { return }
            deliveredClipId = clip.id
            peerClipTime = clip.time
            delegate?.session(self, received: clip)
        case .ping:
            enqueue(.pong, Data())
        case .pong:
            break
        case .state:
            guard let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let interactive = obj["interactive"] as? Bool else { return }
            let was = peerInteractive
            peerInteractive = interactive
            delegate?.sessionChanged(self)
            if interactive && !was { delegate?.sessionBecameInteractive(self) }
        }
    }

    // MARK: sending

    private func enqueue(_ type: FrameType, _ body: Data) {
        control.append((type, body))
        pump()
    }

    /// Sends one frame at a time; the next goes out when the previous has been handed to the
    /// network stack, which gives back-pressure for large transfers.
    private func pump() {
        guard !sending, !closed, let keys else { return }
        var next: (FrameType, Data)?
        if !control.isEmpty {
            next = control.removeFirst()
        } else if let clip = active {
            do {
                next = try clip.nextFrame()
            } catch {
                Log.info("could not read clip while sending: \(error.localizedDescription)")
                active = nil
            }
            if next == nil, clip.finished, active === clip {
                active = nil
                deliveredClipId = clip.clip.id
                Log.info("sent to \(peerName): \(clip.clip.summary)")
                delegate?.sessionChanged(self)
            }
        }
        guard let (type, body) = next else { return }
        let frame = WireCrypto.seal(type: type, body: body, key: keys.send, counter: sendCounter)
        sendCounter += 1
        sending = true
        conn.send(content: frame, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.sending = false
            if let error {
                self.close("send failed: \(error)")
                return
            }
            self.pump()
        })
    }
}
