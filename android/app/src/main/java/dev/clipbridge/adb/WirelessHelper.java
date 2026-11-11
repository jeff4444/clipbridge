package dev.clipbridge.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.conscrypt.Conscrypt;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Drives the on-device ADB daemon: pair once with a six-digit code, then connect and launch the
 * background helper. This is what replaces plugging the phone into the Mac.
 *
 * Capture and syncing are done by the helper (daemon/Main.java); this class only starts it.
 */
public final class WirelessHelper {
    public static final String CONNECT_SERVICE = "_adb-tls-connect._tcp";
    public static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";

    private static boolean cryptoReady;

    public interface Log {
        void line(String message);
    }

    private WirelessHelper() {
    }

    /** Make TLS 1.3 available to the ADB library on every OEM build. Safe to call repeatedly. */
    public static synchronized void initCrypto() {
        if (cryptoReady) return;
        try {
            // Prefer the bundled Conscrypt so pairing never depends on hidden platform classes.
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable t) {
            // Older Conscrypt or a provider clash: fall back to the platform one below.
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt");
            } catch (Throwable ignored) {
            }
        }
        cryptoReady = true;
    }

    /** Which step of setup went wrong, so the user can be told what to do about it. */
    public static final class SetupException extends Exception {
        public enum Stage {NO_DIALOG, PAIR, LAUNCH}

        public final Stage stage;

        SetupException(Stage stage, String message, @Nullable Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }
    }

    /**
     * Pair using the "Pair device with pairing code" dialog that is open on this phone right now,
     * then connect and start the helper. Blocking; call off the main thread.
     *
     * @param code       the six-digit code from the dialog
     * @param pairPort   the port from the dialog, or 0 to look it up (it changes every time the
     *                   dialog opens, so it is looked up now and never remembered)
     * @param configJson config to drop in place first (pairing key + Mac hosts); null to leave as is
     */
    public static synchronized void pairAndLaunch(@NonNull Context context, @NonNull String code, int pairPort,
                                     @Nullable String configJson, @NonNull Log log) throws SetupException {
        String host;
        int port = pairPort;
        if (port <= 0) {
            log.line("looking up the pairing port …");
            Endpoint e = null;
            try {
                e = discoverEndpoint(context, PAIRING_SERVICE, 10000, log);
            } catch (InterruptedException ignored) {
            }
            if (e == null) throw new SetupException(SetupException.Stage.NO_DIALOG, "pairing dialog not found", null);
            host = e.host;
            port = e.port;
        } else {
            // The pairing server listens on the Wi-Fi address, not on loopback.
            host = ownIpv4();
            if (host == null) host = "127.0.0.1";
        }
        try {
            initCrypto();
            log.line("pairing on port " + port + " …");
            ClipAdbManager.getInstance(context).pair(host, port, code);
            log.line("paired");
        } catch (Throwable t) {
            throw new SetupException(SetupException.Stage.PAIR, String.valueOf(t), t);
        }
        try {
            connectAndLaunch(context, host, 0, configJson, log);
        } catch (Throwable t) {
            throw new SetupException(SetupException.Stage.LAUNCH, String.valueOf(t), t);
        }
    }

    /** As above with everything given explicitly (used by the debug test hooks). */
    public static synchronized void pairAndLaunch(@NonNull Context context, @NonNull String host, int pairPort,
                                     @NonNull String code, int connectPort, @Nullable String configJson,
                                     @NonNull Log log) throws Exception {
        initCrypto();
        ClipAdbManager mgr = ClipAdbManager.getInstance(context);
        log.line("pairing with " + host + ":" + pairPort + " …");
        mgr.pair(host, pairPort, code);
        log.line("paired");
        connectAndLaunch(context, host, connectPort, configJson, log);
    }

    /**
     * Connect using the key authorised at pairing time (no code), and start the helper.
     * Synchronized on this class together with {@link #pairAndLaunch}: there is one ADB connection
     * manager, so only one pairing/launch may run at a time (boot job, watchdog and UI can coincide).
     */
    public static synchronized void connectAndLaunch(@NonNull Context context, @NonNull String host, int connectPort,
                                        @Nullable String configJson, @NonNull Log log) throws Exception {
        initCrypto();
        ClipAdbManager mgr = ClipAdbManager.getInstance(context);
        if (connectPort <= 0) {
            log.line("looking for adbd over mDNS …");
            connectPort = discoverPort(context, CONNECT_SERVICE, 8000, log);
            if (connectPort <= 0) throw new IllegalStateException("Wireless debugging not found on this phone");
            log.line("found adbd on port " + connectPort);
        }
        log.line("connecting to " + host + ":" + connectPort + " …");
        mgr.connect(host, connectPort);
        log.line("connected");
        try {
            // "exec:" is a raw pipe (no PTY). The launcher stays alive until the helper is a running,
            // detached session, then exits, so the helper survives this stream closing.
            String out = runLauncher(mgr.openStream("exec:" + launchScript(context, configJson)), 15000);
            log.line("launch: " + out.trim());
        } finally {
            try {
                mgr.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Run the launcher and wait for it to finish, the way `adb shell <cmd>` does. The launcher
     * detaches the long-running helper and exits, so the stream reaches EOF on its own within a
     * moment. We only force-close as a safety net on timeout — never before the launcher has
     * returned, which would kill the not-yet-detached helper.
     */
    private static String runLauncher(@NonNull AdbStream stream, long timeoutMs) {
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = stream.openInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf, 0, buf.length)) != -1) {
                    synchronized (sb) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "adb-launch-read");
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        if (reader.isAlive()) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    /** Bootstrap run as adb-shell (uid 2000): write config, then start the self-restarting helper. */
    private static String launchScript(@NonNull Context context, @Nullable String configJson) {
        String pkg = context.getPackageName();
        StringBuilder s = new StringBuilder();
        s.append("D=/data/local/tmp/clipbridge; mkdir -p $D && chmod 700 $D; ");
        if (configJson != null) {
            // Written through a quoted heredoc, so nothing in it is interpreted by the shell. It must
            // also be plain ASCII (see asciiJson): the Mac's name is in there, and names like
            // "Sam’s MacBook" are not.
            s.append("cat > $D/config.json <<'CBCFG'\n").append(asciiJson(configJson)).append("\nCBCFG\n");
            s.append("chmod 600 $D/config.json; ");
        }
        // Stop every earlier wrapper and helper, found by exact command line rather than by pid file
        // (a pid file only remembers the last one). The patterns cannot match this launcher itself.
        s.append("pkill -f '^sh /data/local/tmp/clipbridge/run\\.sh$' 2>/dev/null; ");
        s.append("pkill -f '^clipbridged' 2>/dev/null; kill $(cat $D/pid 2>/dev/null) 2>/dev/null; ");
        s.append("rm -f $D/pid; sleep 0.4; ");
        s.append("cat > $D/run.sh <<'CBRUN'\n");
        s.append("D=/data/local/tmp/clipbridge\n");
        // Drop every fd inherited from the adb shell, so closing that stream can't reach the helper.
        s.append("exec </dev/null >/dev/null 2>&1\n");
        s.append("echo $$ > $D/wrapper.pid\n");
        s.append("while true; do\n");
        s.append("  APK=$(pm path ").append(pkg).append(" 2>/dev/null | head -1 | cut -d: -f2)\n");
        s.append("  [ -z \"$APK\" ] && exit 0\n");
        s.append("  CLASSPATH=$APK app_process / --nice-name=clipbridged dev.clipbridge.daemon.Main $D\n");
        s.append("  [ $? -eq 0 ] && exit 0\n");
        s.append("  sleep 3\n");
        s.append("done\n");
        s.append("CBRUN\n");
        s.append("chmod 700 $D/run.sh; setsid sh $D/run.sh </dev/null >/dev/null 2>&1 & ");
        // Stay alive until the helper has written its pid and is running: only then is it a detached
        // session of its own that outlives this command. Exiting earlier lets the raw adb service
        // kill the not-yet-detached child.
        s.append("for i in $(seq 1 40); do P=$(cat $D/pid 2>/dev/null); ");
        s.append("[ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null && { echo STARTED; exit 0; }; sleep 0.25; done; ");
        s.append("echo TIMEOUT");
        return s.toString();
    }

    /**
     * The same JSON with every non-ASCII character written as a \\uXXXX escape.
     *
     * The adb library sizes the buffer for a command by its length in characters and then writes it
     * as UTF-8, so one multi-byte character anywhere in the launch command (a curly apostrophe in
     * the Mac's name is enough) overflows it. Non-ASCII can only occur inside JSON strings, where
     * the escape means exactly the same thing.
     */
    static String asciiJson(@NonNull String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' '); // only ever between tokens; a raw newline would end the heredoc line
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    /** A resolved adb mDNS service. */
    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** The port of this phone's own instance of an adb mDNS service, or -1. */
    public static int discoverPort(@NonNull Context context, @NonNull String serviceType, long timeoutMs,
                                   @NonNull Log log) throws InterruptedException {
        Endpoint e = discoverEndpoint(context, serviceType, timeoutMs, log);
        return e != null ? e.port : -1;
    }

    /**
     * Find this phone's own instance of an adb mDNS service.
     *
     * mDNS shows every device on the network, and other phones with Wireless debugging on advertise
     * the very same service types, so each candidate is resolved and kept only if its address is
     * one of ours. Resolves run one at a time: before Android 14 the system rejects overlapping ones.
     */
    @Nullable
    public static Endpoint discoverEndpoint(@NonNull Context context, @NonNull String serviceType, long timeoutMs,
                                            @NonNull Log log) throws InterruptedException {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        Finder finder = new Finder(nsd, localAddresses());
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, finder);
        } catch (Throwable t) {
            log.line("mDNS discovery could not start: " + t);
            return null;
        }
        try {
            finder.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(finder);
            } catch (Throwable ignored) {
            }
        }
        Endpoint e = finder.result;
        log.line(serviceType + ": " + (e != null ? "found on port " + e.port : "not found on this phone")
                + (finder.elsewhere > 0 ? " (" + finder.elsewhere + " on other devices ignored)" : "")
                + (finder.failed > 0 ? " (" + finder.failed + " could not be resolved)" : ""));
        return e;
    }

    private static final class Finder implements NsdManager.DiscoveryListener {
        private final NsdManager nsd;
        private final Set<InetAddress> mine;
        private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
        private final Map<String, Integer> attempts = new HashMap<>();
        private boolean busy;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Endpoint result;
        volatile int elsewhere;
        volatile int failed;

        Finder(NsdManager nsd, Set<InetAddress> mine) {
            this.nsd = nsd;
            this.mine = mine;
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            synchronized (this) {
                pending.add(info);
            }
            pump();
        }

        private void pump() {
            final NsdServiceInfo info;
            synchronized (this) {
                if (busy || result != null) return;
                info = pending.poll();
                if (info == null) return;
                busy = true;
            }
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        String host = ownAddressOf(resolved);
                        if (host != null) {
                            result = new Endpoint(host, resolved.getPort());
                            done.countDown();
                        } else {
                            elsewhere++;
                        }
                        next();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo failedInfo, int errorCode) {
                        retryLater(info);
                        next();
                    }
                });
            } catch (Throwable t) {
                retryLater(info);
                next();
            }
        }

        private void retryLater(NsdServiceInfo info) {
            synchronized (this) {
                String name = String.valueOf(info.getServiceName());
                Integer n = attempts.get(name);
                int tries = n == null ? 1 : n + 1;
                attempts.put(name, tries);
                if (tries < 4) pending.add(info);
                else failed++;
            }
        }

        private void next() {
            synchronized (this) {
                busy = false;
            }
            pump();
        }

        /** The address to reach this service at if it is on this phone, else null. */
        @Nullable
        private String ownAddressOf(NsdServiceInfo resolved) {
            List<InetAddress> addresses = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 34) {
                addresses.addAll(resolved.getHostAddresses());
            } else if (resolved.getHost() != null) {
                addresses.add(resolved.getHost());
            }
            if (addresses.isEmpty()) return null;
            boolean ours = mine.isEmpty(); // could not list our own addresses: do not filter
            for (InetAddress a : addresses) {
                if (mine.contains(a)) ours = true;
            }
            if (!ours) return null;
            for (InetAddress a : addresses) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
            String v4 = ownIpv4();
            return v4 != null ? v4 : addresses.get(0).getHostAddress();
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            done.countDown();
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }
    }

    /** Every address on this phone's own network interfaces. */
    static Set<InetAddress> localAddresses() {
        Set<InetAddress> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return out;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                out.addAll(Collections.list(ni.getInetAddresses()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** This phone's IPv4 address on the local network (Wi-Fi first), or null. */
    @Nullable
    static String ownIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null && a.isSiteLocalAddress()) fallback = a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
