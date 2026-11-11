package dev.clipbridge.daemon;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ClipBridge wire protocol, v1. See PROTOCOL.md at the repo root.
 *
 * Handshake (plaintext, both directions): "CLBR" | version(1) | nonce(16).
 * Session keys: HKDF-SHA256(ikm = pre-shared key, salt = initiatorNonce || responderNonce),
 * one key per direction. Every later frame is
 *   uint32 length | AES-256-GCM(type(1) | body) with a per-direction counter nonce.
 */
final class Wire {
    static final byte[] MAGIC = {'C', 'L', 'B', 'R'};
    static final int VERSION = 1;

    static final int T_HELLO = 1;
    static final int T_CLIP = 2;
    static final int T_DATA = 3;
    static final int T_END = 4;
    static final int T_PING = 5;
    static final int T_PONG = 6;
    static final int T_STATE = 7;

    static final int CHUNK = 256 * 1024;
    private static final int MAX_FRAME = (1 << 20) + 64;
    static final byte[] EMPTY = new byte[0];

    static final class Frame {
        final int type;
        final byte[] body;

        Frame(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final DataInputStream in;
    private final OutputStream out;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private long sendCounter;
    private long recvCounter;

    private Wire(DataInputStream in, OutputStream out, byte[] sendKey, byte[] recvKey) {
        this.in = in;
        this.out = out;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.recvKey = new SecretKeySpec(recvKey, "AES");
    }

    /** Dial side of the handshake. */
    static Wire initiate(Socket socket, byte[] psk) throws IOException, GeneralSecurityException {
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        byte[] mine = new byte[16];
        new SecureRandom().nextBytes(mine);
        writeHello(out, mine);
        byte[] theirs = readHello(in);
        byte[] salt = concat(mine, theirs);
        return new Wire(in, out, hkdf(psk, salt, "clipbridge v1 i2r"), hkdf(psk, salt, "clipbridge v1 r2i"));
    }

    /** Accept side of the handshake. */
    static Wire respond(Socket socket, byte[] psk) throws IOException, GeneralSecurityException {
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        byte[] theirs = readHello(in);
        byte[] mine = new byte[16];
        new SecureRandom().nextBytes(mine);
        writeHello(out, mine);
        byte[] salt = concat(theirs, mine);
        return new Wire(in, out, hkdf(psk, salt, "clipbridge v1 r2i"), hkdf(psk, salt, "clipbridge v1 i2r"));
    }

    private static void writeHello(OutputStream out, byte[] nonce) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(21);
        b.write(MAGIC);
        b.write(VERSION);
        b.write(nonce);
        out.write(b.toByteArray());
        out.flush();
    }

    private static byte[] readHello(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("bad magic");
        int version = in.readUnsignedByte();
        if (version != VERSION) throw new IOException("unsupported protocol version " + version);
        byte[] nonce = new byte[16];
        in.readFully(nonce);
        return nonce;
    }

    void send(int type, byte[] body) throws IOException {
        send(type, body, 0, body.length);
    }

    synchronized void send(int type, byte[] body, int off, int len) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, nonce(sendCounter++)));
            byte[] frame = new byte[4 + 1 + len + 16];
            int n = c.update(new byte[]{(byte) type}, 0, 1, frame, 4);
            n += c.doFinal(body, off, len, frame, 4 + n);
            frame[0] = (byte) (n >>> 24);
            frame[1] = (byte) (n >>> 16);
            frame[2] = (byte) (n >>> 8);
            frame[3] = (byte) n;
            out.write(frame, 0, 4 + n);
            out.flush();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Blocking; only ever called from the session's reader thread. */
    Frame read() throws IOException {
        int len = in.readInt();
        if (len < 17 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte[] ct = new byte[len];
        in.readFully(ct);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(128, nonce(recvCounter++)));
            byte[] pt = c.doFinal(ct);
            return new Frame(pt[0] & 0xff, Arrays.copyOfRange(pt, 1, pt.length));
        } catch (GeneralSecurityException e) {
            throw new IOException("decrypt failed (wrong pairing key?)", e);
        }
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (counter >>> (8 * i));
        return n;
    }

    /** RFC 5869 HKDF-SHA256, single 32-byte output block. */
    private static byte[] hkdf(byte[] ikm, byte[] salt, String info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        return mac.doFinal();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
