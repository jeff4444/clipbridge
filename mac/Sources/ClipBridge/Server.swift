import Foundation
import Network

/// What the main thread knows about a connected phone.
struct Peer {
    let sessionId: UUID
    let name: String
    let device: String
    let interactive: Bool
    let deliveredClipId: String
    let clipTime: Int64
}

protocol ServerOwner: AnyObject {
    // All called on the main thread.
    func serverPeersChanged(_ peers: [Peer])
    func serverReceived(_ clip: Clip, from peer: Peer)
    func serverWantsCatchUp(_ peer: Peer)
}

/// Listens for the phone helper and owns the sessions. Internals run on `queue`.
final class Server: SessionDelegate {
    private let queue = DispatchQueue(label: "dev.clipbridge.net")
    private let port: UInt16
    private let psk: Data
    private let maxBytes: Int64
    private var listener: NWListener?
    private var livenessTimer: DispatchSourceTimer?
    private var sessions: [UUID: Session] = [:]
    private var currentClipId = ""
    private var currentClipTime: Int64 = 0
    weak var owner: ServerOwner?

    init(port: UInt16, psk: Data, maxBytes: Int64) {
        self.port = port
        self.psk = psk
        self.maxBytes = maxBytes
    }

    func start() {
        queue.async {
            self.listen()
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            t.schedule(deadline: .now() + 15, repeating: 15, leeway: .seconds(2))
            t.setEventHandler { [weak self] in
                guard let self else { return }
                for s in self.sessions.values where s.looksDead { s.close("no longer responding") }
            }
            t.resume()
            self.livenessTimer = t
        }
    }

    /// Tell the server what is on the Mac clipboard so new connections can be greeted with it.
    func setCurrent(id: String, time: Int64) {
        queue.async {
            self.currentClipId = id
            self.currentClipTime = time
        }
    }

    func send(_ clip: Clip, to sessionId: UUID) {
        queue.async { self.sessions[sessionId]?.send(clip) }
    }

    private func listen() {
        listener?.cancel()
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        // Slow keepalive: clears out connections to a phone that left while its screen was off,
        // without waking a sleeping phone more than once every ten minutes.
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 600
        tcp.keepaliveInterval = 15
        tcp.keepaliveCount = 3
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true
        do {
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    Log.info("listening on port \(self?.port ?? 0)")
                case .failed(let error):
                    Log.info("listener failed: \(error); retrying")
                    self?.queue.asyncAfter(deadline: .now() + 3) { self?.listen() }
                default:
                    break
                }
            }
            l.start(queue: queue)
            listener = l
        } catch {
            Log.info("could not listen on port \(port): \(error); retrying")
            queue.asyncAfter(deadline: .now() + 5) { [weak self] in self?.listen() }
        }
    }

    private func accept(_ conn: NWConnection) {
        let s = Session(conn: conn, queue: queue, psk: psk, inbox: Config.inbox, maxBytes: maxBytes)
        s.delegate = self
        sessions[s.id] = s
        s.start()
    }

    private func peer(_ s: Session) -> Peer {
        Peer(sessionId: s.id, name: s.peerName, device: s.peerDevice, interactive: s.peerInteractive,
             deliveredClipId: s.deliveredClipId, clipTime: s.peerClipTime)
    }

    private func publish() {
        let peers = sessions.values.filter { $0.ready }.map { peer($0) }
        DispatchQueue.main.async { self.owner?.serverPeersChanged(peers) }
    }

    // MARK: SessionDelegate (network queue)

    func helloBody(for session: Session) -> Data {
        let obj: [String: Any] = [
            "v": 1,
            "platform": "mac",
            "name": SystemInfo.computerName,
            "clipId": currentClipId,
            "clipTime": currentClipTime,
        ]
        return (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
    }

    func sessionReady(_ session: Session) {
        // A phone that reconnects replaces its previous (probably dead) connection.
        for other in sessions.values where other !== session && other.ready && other.peerDevice == session.peerDevice {
            other.close("replaced by a newer connection")
        }
        publish()
        let p = peer(session)
        DispatchQueue.main.async { self.owner?.serverWantsCatchUp(p) }
    }

    func session(_ session: Session, received clip: Clip) {
        let p = peer(session)
        publish()
        DispatchQueue.main.async { self.owner?.serverReceived(clip, from: p) }
    }

    func sessionBecameInteractive(_ session: Session) {
        let p = peer(session)
        DispatchQueue.main.async { self.owner?.serverWantsCatchUp(p) }
    }

    func sessionChanged(_ session: Session) {
        publish()
    }

    func sessionClosed(_ session: Session) {
        sessions[session.id] = nil
        publish()
    }
}
