import CryptoKit
import Foundation

// ClipBridge wire protocol v1 — see PROTOCOL.md. Must stay in step with
// android/app/src/main/java/dev/clipbridge/daemon/Wire.java.

enum FrameType: UInt8 {
    case hello = 1, clip = 2, data = 3, end = 4, ping = 5, pong = 6, state = 7
}

struct SessionKeys {
    let send: SymmetricKey
    let recv: SymmetricKey
}

enum WireError: Error {
    case badFrame
}

enum WireCrypto {
    static let magic = Data("CLBR".utf8)
    static let version: UInt8 = 1
    static let handshakeLength = 21
    static let chunk = 256 * 1024
    static let maxFrame = (1 << 20) + 64

    static func handshake(nonce: Data) -> Data {
        var d = magic
        d.append(version)
        d.append(nonce)
        return d
    }

    /// Returns the peer's nonce, or nil if this is not a ClipBridge v1 handshake.
    static func parseHandshake(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count == handshakeLength, Array(b[0..<4]) == [UInt8](magic), b[4] == version else { return nil }
        return Data(b[5..<21])
    }

    static func randomNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    static func derive(psk: Data, initiatorNonce: Data, responderNonce: Data, weAreInitiator: Bool) -> SessionKeys {
        let ikm = SymmetricKey(data: psk)
        let salt = initiatorNonce + responderNonce
        let i2r = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("clipbridge v1 i2r".utf8), outputByteCount: 32)
        let r2i = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("clipbridge v1 r2i".utf8), outputByteCount: 32)
        return weAreInitiator ? SessionKeys(send: i2r, recv: r2i) : SessionKeys(send: r2i, recv: i2r)
    }

    private static func nonce(_ counter: UInt64) -> AES.GCM.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { bytes[11 - i] = UInt8((counter >> (8 * UInt64(i))) & 0xff) }
        return try! AES.GCM.Nonce(data: Data(bytes))
    }

    /// length(4, big endian) | AES-256-GCM(type | body) | tag(16)
    static func seal(type: FrameType, body: Data, key: SymmetricKey, counter: UInt64) -> Data {
        var plain = Data(capacity: body.count + 1)
        plain.append(type.rawValue)
        plain.append(body)
        let box = try! AES.GCM.seal(plain, using: key, nonce: nonce(counter))
        let n = UInt32(box.ciphertext.count + box.tag.count)
        var out = Data(capacity: Int(n) + 4)
        out.append(contentsOf: [UInt8(n >> 24), UInt8((n >> 16) & 0xff), UInt8((n >> 8) & 0xff), UInt8(n & 0xff)])
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    static func open(_ sealed: Data, key: SymmetricKey, counter: UInt64) throws -> (UInt8, Data) {
        guard sealed.count >= 17 else { throw WireError.badFrame }
        let ct = Data(sealed.prefix(sealed.count - 16))
        let tag = Data(sealed.suffix(16))
        let box = try AES.GCM.SealedBox(nonce: nonce(counter), ciphertext: ct, tag: tag)
        let plain = try AES.GCM.open(box, using: key)
        guard let type = plain.first else { throw WireError.badFrame }
        return (type, Data(plain.dropFirst()))
    }
}
