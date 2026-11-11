package dev.clipbridge.daemon;

import android.os.IBinder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Log file + logcat. Clipboard contents are never logged, only sizes and types. */
final class Log {
    private static final String TAG = "ClipBridge";
    private static final long MAX_BYTES = 512 * 1024;
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    static void init(File dir) {
        file = new File(dir, "daemon.log");
    }

    static void i(String msg) {
        android.util.Log.i(TAG, msg);
        write(msg);
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        write("ERROR " + msg + (t != null ? ": " + sw : ""));
    }

    private static synchronized void write(String msg) {
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getPath() + ".1");
                old.delete();
                file.renameTo(old);
            }
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(FMT.format(new Date()) + " " + msg + "\n");
            }
        } catch (IOException ignored) {
        }
    }
}

/** Pairing + connection settings pushed by the Mac over adb. */
final class Config {
    byte[] key;
    int port;
    List<String> hosts = new ArrayList<>();
    String macName;
    long maxBytes;
    long mtime;

    static Config load(File f) throws Exception {
        JSONObject o = new JSONObject(new String(Io.readAll(f), StandardCharsets.UTF_8));
        Config c = new Config();
        c.key = Base64.decode(o.getString("key"), Base64.DEFAULT);
        if (c.key.length < 32) throw new IllegalArgumentException("pairing key too short");
        c.port = o.getInt("port");
        JSONArray hosts = o.optJSONArray("hosts");
        if (hosts != null) for (int i = 0; i < hosts.length(); i++) c.hosts.add(hosts.getString(i));
        c.macName = o.optString("name", "Mac");
        c.maxBytes = o.optLong("maxBytes", 100L * 1024 * 1024);
        c.mtime = f.lastModified();
        return c;
    }
}

/** Screen state, via the hidden IPowerManager. */
final class Power {
    private Object service;
    private Method isInteractive;

    Power() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "power");
            service = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            isInteractive = service.getClass().getMethod("isInteractive");
        } catch (Throwable t) {
            Log.e("power service unavailable; assuming screen is always on", t);
        }
    }

    boolean interactive() {
        if (isInteractive == null) return true;
        try {
            return (Boolean) isInteractive.invoke(service);
        } catch (Throwable t) {
            return true;
        }
    }
}

final class Net {
    /** A string that changes whenever the set of usable LAN addresses changes; empty when there is no LAN. */
    static String fingerprint() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName();
                // Wi-Fi, ethernet dongles, USB tethering. Never cellular (rmnet*), so we do not
                // wake the mobile radio trying to reach a LAN address.
                if (!(n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis"))) continue;
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) sb.append(n).append('=').append(a.getHostAddress()).append(';');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}

final class Io {
    static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    static void writeString(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    static String readString(File f) {
        try {
            return new String(readAll(f), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String sha256(byte[] data) {
        return hex(sha256().digest(data));
    }

    static void update(MessageDigest md, File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
    }
}

/**
 * Content-provider access for a process that has no Context: shell out to the platform `content`
 * tool, which runs with the same shell identity (and so the same clipboard URI grants) as we do.
 */
final class ContentIo {
    private static final File DEV_NULL = new File("/dev/null");

    /** Copies the bytes behind a content:// URI into dest. Returns the size, or -1 on failure. */
    static long readToFile(String uri, File dest) {
        Process p = null;
        try {
            p = new ProcessBuilder("content", "read", "--uri", uri).redirectError(DEV_NULL).start();
            long n;
            try (InputStream in = p.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                n = Io.copy(in, out);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return -1;
            return n;
        } catch (Throwable t) {
            Log.e("content read failed", t);
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }

    static String displayName(String uri) {
        Process p = null;
        try {
            p = new ProcessBuilder("content", "query", "--uri", uri, "--projection", "_display_name")
                    .redirectError(DEV_NULL).start();
            String out = new String(Io.readAll(p.getInputStream()), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            // "Row: 0 _display_name=IMG_1234.png"
            int i = out.indexOf("_display_name=");
            if (i < 0) return null;
            String name = out.substring(i + "_display_name=".length());
            int nl = name.indexOf('\n');
            if (nl >= 0) name = name.substring(0, nl);
            name = name.trim();
            return name.isEmpty() || name.equals("NULL") ? null : name;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Streams src into a content:// URI (our own companion app's provider). */
    static boolean writeFromFile(String uri, File src) {
        Process p = null;
        try {
            p = new ProcessBuilder("content", "write", "--uri", uri)
                    .redirectError(DEV_NULL)
                    .redirectOutput(DEV_NULL).start();
            try (InputStream in = new FileInputStream(src); OutputStream out = p.getOutputStream()) {
                Io.copy(in, out);
            }
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Throwable t) {
            Log.e("content write failed", t);
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
