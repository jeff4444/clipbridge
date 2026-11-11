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
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Pair with the phone's adbd, connect, and start the helper. Blocking; call off the main thread.
     *
     * @param pairPort    the port from "Pair device with pairing code"
     * @param code        the six-digit code from that dialog
     * @param connectPort the Wireless debugging port (0 = discover over mDNS)
     * @param configJson  config to drop in place first (pairing key + Mac hosts); null to leave as is
     */
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
            connectPort = discoverPort(context, CONNECT_SERVICE, 8000);
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
            // The JSON is base64 key + numbers + host names: no single quotes, safe in a quoted heredoc.
            s.append("cat > $D/config.json <<'CBCFG'\n").append(configJson).append("\nCBCFG\n");
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

    /** A resolved adb mDNS service. */
    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Resolve one instance of an adb mDNS service to its port. */
    public static int discoverPort(@NonNull Context context, @NonNull String serviceType, long timeoutMs)
            throws InterruptedException {
        Endpoint e = discoverEndpoint(context, serviceType, timeoutMs);
        return e != null ? e.port : -1;
    }

    /** Resolve one instance of an adb mDNS service to its host address and port. */
    @Nullable
    public static Endpoint discoverEndpoint(@NonNull Context context, @NonNull String serviceType, long timeoutMs)
            throws InterruptedException {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        final java.util.concurrent.atomic.AtomicReference<Endpoint> result = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            String host = resolved.getHost() != null ? resolved.getHost().getHostAddress() : null;
                            if (host != null && result.compareAndSet(null, new Endpoint(host, resolved.getPort()))) {
                                done.countDown();
                            }
                        }

                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        }
                    });
                } catch (Throwable ignored) {
                }
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
        };

        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Throwable ignored) {
            }
        }
        return result.get();
    }
}
