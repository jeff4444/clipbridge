package dev.clipbridge.daemon;

import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone half of ClipBridge. Runs as the shell user:
 *
 *   CLASSPATH=<apk> app_process / --nice-name=clipbridged dev.clipbridge.daemon.Main <state dir>
 *
 * It listens for clipboard changes, keeps an encrypted connection to the Mac, and applies
 * clipboard contents coming the other way. It has no UI and no Android Context.
 */
public final class Main {
    static final String VERSION = "0.1.0";
    /** The app file this helper was started from. It moves whenever the app is updated. */
    static final String APK = System.getenv("CLASSPATH");
    static final int STATUS_PORT = 47824;
    static final String AUTHORITY = "dev.clipbridge.clips";
    static final String LABEL = "ClipBridge";
    static final String EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";
    static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    static final int MAX_FILES = 20;
    private static final long[] BACKOFF_MS = {500, 1000, 2000, 3000, 5000, 8000, 15000, 30000};

    static final class Item {
        String kind; // "text" | "html" | "file"
        String mime;
        String name;
        long size;
        byte[] data; // text / html
        File file;   // file
    }

    static final class Clip {
        String id;
        long time;
        boolean sensitive;
        boolean remote;
        String sig;
        final List<Item> items = new ArrayList<>();

        long total() {
            long t = 0;
            for (Item i : items) t += i.size;
            return t;
        }

        /** For the status screen. */
        String human() {
            if (items.isEmpty()) return "nothing";
            Item first = items.get(0);
            if (first.kind.equals("text")) return "text";
            if (items.size() > 1) return items.size() + " files";
            return first.mime != null && first.mime.startsWith("image/") ? "an image" : "a file";
        }

        /** Sizes and types only: safe to log. */
        String describe() {
            StringBuilder sb = new StringBuilder();
            for (Item i : items) sb.append(i.kind).append('(').append(i.mime).append(',').append(i.size).append(") ");
            return sb.toString().trim();
        }
    }

    static final class Session {
        final Socket socket;
        final Wire wire;
        final String via;
        final long startedAt = SystemClock.uptimeMillis();
        final AtomicInteger sendGeneration = new AtomicInteger();
        volatile boolean closed;
        volatile String peerName = "Mac";
        volatile long pingSentAt;
        volatile long lastPingAt = SystemClock.uptimeMillis();

        Session(Socket socket, Wire wire, String via) {
            this.socket = socket;
            this.wire = wire;
            this.via = via;
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final File dir;
    private final File tmp;
    private final File configFile;
    private Config config;
    private ShellClipboard clipboard;
    private Power power;
    private String deviceId;

    private final Object lock = new Object();
    private Session session;         // guarded by lock
    private Clip current;            // guarded by lock: what the clipboard holds, as far as we know
    private volatile boolean interactive = true;
    private volatile long nextAttemptAt;
    private volatile int failures;
    private volatile boolean connecting;
    private volatile String lastGoodHost;
    private volatile long lastSyncAt;
    private volatile String lastSyncWhat = "";
    private final AtomicInteger captureGeneration = new AtomicInteger();
    /** A clip from the Mac that was applied while the phone was locked; checked again after unlock. */
    private volatile ClipData pendingVerify;
    private volatile Clip pendingVerifyClip;

    private final ScheduledExecutorService clipExec = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0] : "/data/local/tmp/clipbridge");
        dir.mkdirs();
        Log.init(dir);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.e("uncaught on " + t.getName(), e);
            System.exit(1); // the wrapper loop restarts us
        });
        try {
            new Main(dir).run();
        } catch (Throwable t) {
            Log.e("fatal", t);
            System.exit(1);
        }
    }

    private Main(File dir) {
        this.dir = dir;
        this.tmp = new File(dir, "tmp");
        this.configFile = new File(dir, "config.json");
    }

    private void run() throws Exception {
        // The status port doubles as a single-instance lock.
        ServerSocket status;
        try {
            status = new ServerSocket(STATUS_PORT, 8, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            Log.i("another instance is already running; exiting");
            System.exit(0);
            return;
        }
        Io.writeString(new File(dir, "pid"), String.valueOf(android.os.Process.myPid()));
        Io.deleteRecursively(tmp);
        tmp.mkdirs();
        config = Config.load(configFile);
        deviceId = Io.readString(new File(dir, "device-id"));
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = randomId();
            Io.writeString(new File(dir, "device-id"), deviceId);
        }
        lastGoodHost = Io.readString(new File(dir, "last-host"));
        power = new Power();
        interactive = power.interactive();
        clipboard = new ShellClipboard();

        // Whatever is on the clipboard at startup counts as "fresh as of now" but has no payload,
        // so a helper restart can neither send nor receive stale contents.
        Clip initial = new Clip();
        initial.id = randomId();
        initial.time = System.currentTimeMillis();
        initial.sig = quickSignature(clipboard.get());
        current = initial;

        clipboard.listen(this::onClipboardChanged);
        Thread st = new Thread(() -> statusLoop(status), "status");
        st.setDaemon(true);
        st.start();

        Log.i("clipbridged " + VERSION + " started pid=" + android.os.Process.myPid() + " sdk=" + Build.VERSION.SDK_INT
                + " mac=" + config.macName + " hosts=" + config.hosts);
        managerLoop();
    }

    // ------------------------------------------------------------------ manager

    private void managerLoop() throws InterruptedException {
        String lan = Net.fingerprint();
        long lastLanCheck = 0;
        // Only watch the app file if it can be seen from here in the first place.
        final File apk = APK != null && new File(APK).exists() ? new File(APK) : null;
        long lastApkCheck = SystemClock.uptimeMillis();
        while (true) {
            long now = SystemClock.uptimeMillis();
            boolean pingNow = false;

            // An app update replaces the file this code was loaded from. Stop with a non-zero code
            // so the wrapper loop starts the new version (or ends, if the app was uninstalled).
            if (apk != null && now - lastApkCheck > 5000) {
                lastApkCheck = now;
                if (!apk.exists()) {
                    Log.i("the app was updated or removed; restarting from the installed copy");
                    System.exit(3);
                }
            }

            boolean inter = power.interactive();
            if (inter != interactive) {
                interactive = inter;
                Log.i(inter ? "screen on" : "screen off");
                if (inter) {
                    kickConnect();
                    pingNow = true;
                }
                sendState();
            }

            if (now - lastLanCheck > 2000) {
                lastLanCheck = now;
                String fp = Net.fingerprint();
                if (!fp.equals(lan)) {
                    lan = fp;
                    Log.i("network changed: " + (fp.isEmpty() ? "no LAN" : fp));
                    kickConnect();
                    pingNow = true;
                }
            }

            if (pendingVerify != null && inter && now - lastVerifyAt > 1000) {
                lastVerifyAt = now;
                verifyPending();
            }

            Session s;
            synchronized (lock) {
                s = session;
            }
            if (s == null) {
                if (!connecting && now >= nextAttemptAt) {
                    connecting = true;
                    connector.execute(this::attemptConnect);
                }
            } else if (s.pingSentAt != 0 && now - s.pingSentAt > 2500) {
                Log.i("ping timed out; reconnecting");
                s.close();
            } else if (s.pingSentAt == 0 && (pingNow || (inter && now - s.lastPingAt > 30000))) {
                s.pingSentAt = now;
                s.lastPingAt = now;
                final Session fs = s;
                sender.execute(() -> {
                    try {
                        fs.wire.send(Wire.T_PING, Wire.EMPTY);
                    } catch (IOException e) {
                        fs.close();
                    }
                });
            }
            // None of this runs while the phone is suspended; the slower tick only matters when
            // the screen is off but something else is keeping the CPU awake.
            Thread.sleep(inter ? 250 : 500);
        }
    }

    /** Retry quickly while the screen is on (the user may paste any moment); lazily otherwise. */
    private long nextBackoff() {
        long delay = BACKOFF_MS[Math.min(failures++, BACKOFF_MS.length - 1)];
        return interactive ? Math.min(delay, 5000) : delay;
    }

    private long lastVerifyAt;

    /**
     * The clipboard cannot be read while the phone is locked, so a clip applied on the lock screen
     * cannot be confirmed straight away. Once the phone is unlocked, make sure it really is there.
     */
    private void verifyPending() {
        ClipData want = pendingVerify;
        Clip clip = pendingVerifyClip;
        if (want == null) return;
        ClipData have = clipboard.get();
        if (have == null) return; // still locked
        pendingVerify = null;
        pendingVerifyClip = null;
        synchronized (lock) {
            if (current != clip) return; // something newer has been copied since
        }
        if (sameClip(have, want)) {
            Log.i("clip applied while locked is in place");
        } else {
            Log.i("clip applied while locked did not stick; applying again");
            clipboard.set(want);
        }
    }

    private static boolean sameClip(ClipData a, ClipData b) {
        if (a.getItemCount() == 0 || b.getItemCount() == 0) return false;
        ClipData.Item x = a.getItemAt(0), y = b.getItemAt(0);
        if (x.getUri() != null || y.getUri() != null) return x.getUri() != null && x.getUri().equals(y.getUri());
        return x.getText() != null && y.getText() != null && x.getText().toString().equals(y.getText().toString());
    }

    private void kickConnect() {
        failures = 0;
        nextAttemptAt = 0;
    }

    private void attemptConnect() {
        try {
            if (configFile.lastModified() != config.mtime) {
                try {
                    config = Config.load(configFile);
                    Log.i("config reloaded; hosts=" + config.hosts);
                } catch (Exception e) {
                    Log.e("config reload failed", e);
                }
            }
            Config cfg = config;
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add("127.0.0.1"); // adb reverse tunnel, when the phone is plugged in
            if (!Net.fingerprint().isEmpty()) {
                if (lastGoodHost != null && !lastGoodHost.isEmpty()) candidates.add(lastGoodHost);
                candidates.addAll(cfg.hosts);
            }
            for (String host : candidates) {
                InetAddress[] addrs;
                try {
                    addrs = InetAddress.getAllByName(host);
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress a : addrs) {
                    if (!(a instanceof Inet4Address)) continue;
                    Socket sock = new Socket();
                    try {
                        sock.setTcpNoDelay(true);
                        sock.connect(new InetSocketAddress(a, cfg.port), a.isLoopbackAddress() ? 300 : 1500);
                        sock.setSoTimeout(3000);
                        Wire wire = Wire.initiate(sock, cfg.key);
                        sock.setSoTimeout(0);
                        startSession(new Session(sock, wire, a.getHostAddress()));
                        if (!a.isLoopbackAddress()) {
                            lastGoodHost = a.getHostAddress();
                            Io.writeString(new File(dir, "last-host"), lastGoodHost);
                        }
                        return;
                    } catch (Exception e) {
                        try {
                            sock.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            nextAttemptAt = SystemClock.uptimeMillis() + nextBackoff();
        } catch (Throwable t) {
            Log.e("connect attempt crashed", t);
            nextAttemptAt = SystemClock.uptimeMillis() + 5000;
        } finally {
            connecting = false;
        }
    }

    private void startSession(Session s) throws Exception {
        Clip c;
        synchronized (lock) {
            c = current;
        }
        JSONObject hello = new JSONObject();
        hello.put("v", Wire.VERSION);
        hello.put("platform", "android");
        hello.put("name", Build.MODEL);
        hello.put("device", deviceId);
        hello.put("clipId", c.id);
        hello.put("clipTime", c.time);
        hello.put("interactive", interactive);
        s.wire.send(Wire.T_HELLO, bytes(hello)); // throws before we commit to this session
        Session old;
        synchronized (lock) {
            old = session;
            session = s;
        }
        if (old != null) old.close();
        Thread t = new Thread(() -> readLoop(s), "reader");
        t.setDaemon(true);
        t.start();
        Log.i("connected to Mac via " + s.via);
    }

    private void onSessionClosed(Session s) {
        boolean wasCurrent;
        synchronized (lock) {
            wasCurrent = session == s;
            if (wasCurrent) session = null;
        }
        if (!wasCurrent) return;
        long lived = SystemClock.uptimeMillis() - s.startedAt;
        Log.i("disconnected after " + lived / 1000 + "s");
        if (lived < 5000) {
            // Connected but dropped straight away (e.g. key mismatch): back off instead of spinning.
            nextAttemptAt = SystemClock.uptimeMillis() + nextBackoff();
        } else {
            failures = 0;
            nextAttemptAt = SystemClock.uptimeMillis() + 300;
        }
    }

    private void sendState() {
        Session s;
        synchronized (lock) {
            s = session;
        }
        if (s == null) return;
        final boolean inter = interactive;
        sender.execute(() -> {
            try {
                s.wire.send(Wire.T_STATE, bytes(new JSONObject().put("interactive", inter)));
            } catch (Exception e) {
                s.close();
            }
        });
    }

    // ------------------------------------------------------------------ phone -> Mac

    /** Binder thread. Android often fires twice per copy, so debounce and coalesce. */
    private void onClipboardChanged() {
        final int gen = captureGeneration.incrementAndGet();
        clipExec.schedule(() -> {
            if (captureGeneration.get() != gen) return;
            try {
                captureLocal();
            } catch (Throwable t) {
                Log.e("capture failed", t);
            }
        }, 120, TimeUnit.MILLISECONDS);
    }

    private void captureLocal() {
        ClipData cd = clipboard.get();
        if (cd == null || cd.getItemCount() == 0) return; // locked, or cleared
        ClipData.Item first = cd.getItemAt(0);
        if (first.getUri() != null && AUTHORITY.equals(first.getUri().getAuthority())) return; // we put it there
        String quick = quickSignature(cd);
        Clip prev;
        synchronized (lock) {
            prev = current;
        }
        if (quick != null && prev != null && quick.equals(prev.sig)) return; // unchanged, or our own echo

        Clip clip = buildLocal(cd);
        if (clip == null) return;
        // Strictly later than anything seen so far, even if the Mac's clock runs ahead of ours.
        if (prev != null && clip.time <= prev.time) clip.time = prev.time + 1;
        if (prev != null && clip.sig.equals(prev.sig)) {
            discard(clip);
            return;
        }
        Session s;
        synchronized (lock) {
            current = clip;
            s = session;
        }
        if (prev != null && !prev.remote) discard(prev);
        Log.i("local copy: " + clip.describe() + (clip.sensitive ? " [sensitive]" : ""));
        if (s != null) sendClip(s, clip);
        else kickConnect();
    }

    /** Cheap signature for text clips; null for URI clips (their content has to be read to compare). */
    private static String quickSignature(ClipData cd) {
        if (cd == null || cd.getItemCount() == 0) return "empty";
        ClipData.Item it = cd.getItemAt(0);
        if (it.getUri() != null) return null;
        if (it.getText() != null) return "t:" + Io.sha256(it.getText().toString().getBytes(StandardCharsets.UTF_8));
        return "other";
    }

    private Clip buildLocal(ClipData cd) {
        ClipDescription desc = cd.getDescription();
        Clip clip = new Clip();
        clip.id = randomId();
        clip.time = System.currentTimeMillis();
        PersistableBundle extras = desc.getExtras();
        clip.sensitive = extras != null && extras.getBoolean(EXTRA_IS_SENSITIVE, false);

        ClipData.Item first = cd.getItemAt(0);
        if (first.getUri() != null && "content".equals(first.getUri().getScheme())) {
            MessageDigest md = Io.sha256();
            int count = Math.min(cd.getItemCount(), MAX_FILES);
            for (int i = 0; i < count; i++) {
                Uri uri = cd.getItemAt(i).getUri();
                if (uri == null || !"content".equals(uri.getScheme())) continue;
                File f = new File(tmp, "out-" + clip.id + "-" + i);
                long size = ContentIo.readToFile(uri.toString(), f);
                if (size <= 0) {
                    f.delete();
                    Log.i("could not read copied item " + i + " (" + uri.getAuthority() + ")");
                    continue;
                }
                if (clip.total() + size > config.maxBytes) {
                    f.delete();
                    Log.i("copied item too large to sync (" + size + " bytes)");
                    continue;
                }
                Item item = new Item();
                item.kind = "file";
                item.mime = pickMime(desc, count);
                item.name = ContentIo.displayName(uri.toString());
                if (item.name == null) item.name = fallbackName(uri, item.mime);
                if (item.mime == null) item.mime = mimeFromName(item.name);
                item.size = size;
                item.file = f;
                clip.items.add(item);
                try {
                    Io.update(md, f);
                } catch (IOException ignored) {
                }
            }
            if (!clip.items.isEmpty()) {
                clip.sig = "u:" + Io.hex(md.digest());
                return clip;
            }
        }
        CharSequence text = first.getText();
        if (text == null && first.getUri() != null) text = first.getUri().toString();
        if (text == null || text.length() == 0) return null;
        byte[] utf8 = text.toString().getBytes(StandardCharsets.UTF_8);
        if (utf8.length > config.maxBytes) return null;
        Item t = new Item();
        t.kind = "text";
        t.mime = "text/plain";
        t.name = "";
        t.data = utf8;
        t.size = utf8.length;
        clip.items.add(t);
        String html = first.getHtmlText();
        if (html != null && !html.isEmpty()) {
            Item h = new Item();
            h.kind = "html";
            h.mime = "text/html";
            h.name = "";
            h.data = html.getBytes(StandardCharsets.UTF_8);
            h.size = h.data.length;
            clip.items.add(h);
        }
        clip.sig = "t:" + Io.sha256(utf8);
        return clip;
    }

    private static String pickMime(ClipDescription desc, int itemCount) {
        if (itemCount != 1) return null; // one description for several items: derive per file from its name
        for (int i = 0; i < desc.getMimeTypeCount(); i++) {
            String m = desc.getMimeType(i);
            if (m == null || m.contains("*") || m.equals("text/uri-list") || m.equals("text/plain")) continue;
            return m;
        }
        return null;
    }

    private static String fallbackName(Uri uri, String mime) {
        String last = uri.getLastPathSegment();
        if (last != null && last.contains(".") && last.length() < 120 && !last.contains("/")) return last;
        String ext = "bin";
        if (mime != null) {
            if (mime.equals("image/png")) ext = "png";
            else if (mime.equals("image/jpeg")) ext = "jpg";
            else if (mime.equals("image/gif")) ext = "gif";
            else if (mime.equals("image/webp")) ext = "webp";
            else if (mime.equals("application/pdf")) ext = "pdf";
            else if (mime.startsWith("text/")) ext = "txt";
        }
        return "clipboard." + ext;
    }

    static String mimeFromName(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private void discard(Clip c) {
        for (Item i : c.items) if (i.file != null) i.file.delete();
    }

    private void sendClip(Session s, Clip clip) {
        final int gen = s.sendGeneration.incrementAndGet();
        sender.execute(() -> {
            if (s.closed || s.sendGeneration.get() != gen) return;
            try {
                JSONObject header = new JSONObject();
                header.put("id", clip.id);
                header.put("time", clip.time);
                header.put("sensitive", clip.sensitive);
                JSONArray items = new JSONArray();
                for (Item i : clip.items) {
                    items.put(new JSONObject().put("kind", i.kind).put("mime", i.mime).put("name", i.name).put("size", i.size));
                }
                header.put("items", items);
                s.wire.send(Wire.T_CLIP, bytes(header));
                byte[] buf = new byte[Wire.CHUNK];
                for (Item i : clip.items) {
                    if (i.data != null) {
                        for (int off = 0; off < i.data.length; off += Wire.CHUNK) {
                            s.wire.send(Wire.T_DATA, i.data, off, Math.min(Wire.CHUNK, i.data.length - off));
                        }
                    } else {
                        try (InputStream in = new FileInputStream(i.file)) {
                            long left = i.size;
                            while (left > 0) {
                                if (s.sendGeneration.get() != gen) return; // superseded by a newer copy
                                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                                if (n <= 0) throw new IOException("file shrank while sending");
                                s.wire.send(Wire.T_DATA, buf, 0, n);
                                left -= n;
                            }
                        }
                    }
                }
                s.wire.send(Wire.T_END, bytes(new JSONObject().put("id", clip.id)));
                lastSyncAt = System.currentTimeMillis();
                lastSyncWhat = "sent " + clip.human();
                Log.i("sent to Mac: " + clip.describe());
            } catch (Exception e) {
                Log.i("send failed: " + e);
                s.close();
            }
        });
    }

    // ------------------------------------------------------------------ Mac -> phone

    private final class Incoming {
        final Clip clip = new Clip();
        final List<OutputStream> sinks = new ArrayList<>();
        final List<Long> remaining = new ArrayList<>();
        int index;
        boolean rejected;

        Incoming(JSONObject header) throws Exception {
            clip.id = header.getString("id").replaceAll("[^0-9a-fA-F]", "");
            if (clip.id.isEmpty() || clip.id.length() > 32) throw new IOException("bad clip id");
            clip.time = header.optLong("time", System.currentTimeMillis());
            clip.sensitive = header.optBoolean("sensitive", false);
            clip.remote = true;
            JSONArray items = header.getJSONArray("items");
            long total = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                Item it = new Item();
                it.kind = o.getString("kind");
                it.mime = o.optString("mime", "application/octet-stream");
                it.name = o.optString("name", "");
                it.size = o.getLong("size");
                if (it.size < 0) throw new IOException("bad item size");
                total += it.size;
                clip.items.add(it);
            }
            if (total > config.maxBytes || clip.items.isEmpty() || clip.items.size() > MAX_FILES + 2) {
                rejected = true;
                Log.i("incoming clip rejected (" + total + " bytes, " + clip.items.size() + " items)");
                return;
            }
            for (int i = 0; i < clip.items.size(); i++) {
                Item it = clip.items.get(i);
                if (it.kind.equals("file")) {
                    it.file = new File(tmp, "in-" + clip.id + "-" + i);
                    sinks.add(new FileOutputStream(it.file));
                } else {
                    sinks.add(new ByteArrayOutputStream((int) Math.min(it.size, 1 << 20)));
                }
                remaining.add(it.size);
            }
        }

        void append(byte[] body) throws IOException {
            if (rejected) return;
            int off = 0;
            while (off < body.length) {
                while (index < remaining.size() && remaining.get(index) == 0) index++;
                if (index >= remaining.size()) throw new IOException("more data than announced");
                int n = (int) Math.min(body.length - off, remaining.get(index));
                sinks.get(index).write(body, off, n);
                remaining.set(index, remaining.get(index) - n);
                off += n;
            }
        }

        boolean finish() throws IOException {
            if (rejected) return false;
            for (long r : remaining) if (r != 0) return false;
            for (int i = 0; i < sinks.size(); i++) {
                OutputStream o = sinks.get(i);
                o.close();
                if (o instanceof ByteArrayOutputStream) clip.items.get(i).data = ((ByteArrayOutputStream) o).toByteArray();
            }
            return true;
        }

        void discard() {
            for (OutputStream o : sinks) {
                try {
                    o.close();
                } catch (IOException ignored) {
                }
            }
            Main.this.discard(clip);
        }
    }

    private void readLoop(Session s) {
        Incoming incoming = null;
        try {
            while (true) {
                Wire.Frame f = s.wire.read();
                switch (f.type) {
                    case Wire.T_HELLO:
                        onHello(s, new JSONObject(f.text()));
                        break;
                    case Wire.T_CLIP:
                        if (incoming != null) incoming.discard(); // superseded before it finished
                        incoming = new Incoming(new JSONObject(f.text()));
                        break;
                    case Wire.T_DATA:
                        if (incoming != null) incoming.append(f.body);
                        break;
                    case Wire.T_END:
                        if (incoming != null) {
                            Incoming done = incoming;
                            incoming = null;
                            if (done.finish()) applyRemote(done.clip);
                            done.discard();
                        }
                        break;
                    case Wire.T_PING:
                        s.wire.send(Wire.T_PONG, Wire.EMPTY);
                        break;
                    case Wire.T_PONG:
                        s.pingSentAt = 0;
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            if (!s.closed) Log.i("connection lost: " + t);
        } finally {
            if (incoming != null) incoming.discard();
            s.close();
            onSessionClosed(s);
        }
    }

    private void onHello(Session s, JSONObject hello) {
        s.peerName = hello.optString("name", "Mac");
        String peerClipId = hello.optString("clipId", "");
        long peerClipTime = hello.optLong("clipTime", 0);
        Clip c;
        synchronized (lock) {
            c = current;
        }
        // Whoever copied most recently while we were apart wins.
        if (c != null && !c.remote && !c.items.isEmpty() && !c.id.equals(peerClipId) && c.time > peerClipTime) {
            Log.i("catching Mac up with a copy made while disconnected");
            sendClip(s, c);
        }
    }

    private void applyRemote(Clip clip) {
        ClipData cd;
        Item first = clip.items.get(0);
        if (first.kind.equals("text")) {
            String text = new String(first.data, StandardCharsets.UTF_8);
            String html = null;
            for (Item i : clip.items) if (i.kind.equals("html")) html = new String(i.data, StandardCharsets.UTF_8);
            cd = html != null ? ClipData.newHtmlText(LABEL, text, html) : ClipData.newPlainText(LABEL, text);
            clip.sig = "t:" + Io.sha256(first.data);
        } else if (first.kind.equals("file")) {
            List<Uri> uris = new ArrayList<>();
            Set<String> mimes = new LinkedHashSet<>();
            for (int i = 0; i < clip.items.size(); i++) {
                Item it = clip.items.get(i);
                if (!it.kind.equals("file") || it.file == null) continue;
                String safeName = it.name.replaceAll("[/\\\\\\u0000]", "_");
                if (safeName.isEmpty()) safeName = "clipboard";
                Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                        .appendPath(clip.id).appendPath(String.valueOf(i)).appendPath(safeName)
                        .appendQueryParameter("mime", it.mime).build();
                if (!ContentIo.writeFromFile(uri.toString(), it.file)) {
                    Log.i("could not hand file to the ClipBridge app (is it installed?)");
                    return;
                }
                uris.add(uri);
                mimes.add(it.mime);
            }
            if (uris.isEmpty()) return;
            cd = new ClipData(new ClipDescription(LABEL, mimes.toArray(new String[0])), new ClipData.Item(uris.get(0)));
            for (int i = 1; i < uris.size(); i++) cd.addItem(new ClipData.Item(uris.get(i)));
            clip.sig = "u:remote:" + clip.id;
        } else {
            return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_SUPPRESS_OVERLAY, true); // no "Copied" pop-up for copies made on the Mac
        if (clip.sensitive) extras.putBoolean(EXTRA_IS_SENSITIVE, true);
        cd.getDescription().setExtras(extras);

        Clip prev;
        synchronized (lock) {
            prev = current;
            current = clip; // before set(), so our own change callback is recognised as an echo
        }
        if (!clipboard.set(cd)) {
            synchronized (lock) {
                if (current == clip) current = prev;
            }
            return;
        }
        if (prev != null && !prev.remote) discard(prev);
        if (clipboard.get() == null) { // locked: confirm after unlock
            pendingVerifyClip = clip;
            pendingVerify = cd;
        } else {
            pendingVerify = null;
            pendingVerifyClip = null;
        }
        lastSyncAt = System.currentTimeMillis();
        lastSyncWhat = "received " + clip.human();
        Log.i("applied from Mac: " + clip.describe() + (clip.sensitive ? " [sensitive]" : ""));
    }

    // ------------------------------------------------------------------ status (for the companion app)

    private void statusLoop(ServerSocket server) {
        while (true) {
            try (Socket c = server.accept()) {
                Session s;
                synchronized (lock) {
                    s = session;
                }
                JSONObject o = new JSONObject();
                o.put("version", VERSION);
                o.put("apk", APK != null ? APK : "");
                o.put("pid", android.os.Process.myPid());
                o.put("connected", s != null);
                o.put("mac", config.macName);
                o.put("via", s != null ? s.via : "");
                o.put("lastSyncAt", lastSyncAt);
                o.put("lastSync", lastSyncWhat);
                c.getOutputStream().write(bytes(o));
            } catch (Throwable t) {
                // keep serving
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] bytes(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String randomId() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        return Io.hex(b);
    }
}
