package dev.clipbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Status + routing screen. The actual syncing is done by the shell-user helper (daemon/Main.java);
 * this screen reports how it is going and, when the helper is down after a reboot, quietly brings it
 * back using the key paired during setup. First-time users are sent to {@link SetupActivity}.
 */
public final class MainActivity extends Activity {
    private static final int STATUS_PORT = 47824;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView headline;
    private TextView detail;
    private TextView last;
    private Button action;
    private boolean resumed;
    private boolean reconnecting;
    private boolean reconnectTried;
    private boolean refreshTried;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        int pad = dp(24);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text(34, true);
        title.setText("ClipBridge");
        col.addView(title);

        headline = text(20, true);
        headline.setPadding(0, dp(28), 0, dp(8));
        col.addView(headline);

        detail = text(16, false);
        detail.setLineSpacing(dp(3), 1f);
        col.addView(detail);

        action = new Button(this);
        action.setAllCaps(false);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        action.setLayoutParams(lp);
        action.setVisibility(android.view.View.GONE);
        col.addView(action);

        last = text(14, false);
        last.setPadding(0, dp(24), 0, 0);
        last.setAlpha(0.7f);
        col.addView(last);

        TextView licences = text(13, false);
        licences.setText("Version " + versionName() + "  ·  Open-source licences");
        licences.setPadding(0, dp(36), 0, dp(8));
        licences.setAlpha(0.6f);
        licences.setOnClickListener(v -> startActivity(new Intent(this, NoticesActivity.class)));
        col.addView(licences);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        render(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reconnectTried = false;
        refreshTried = false;
        if (prefs.isPaired()) Reconnector.scheduleWatchdog(this); // make sure the safety net is armed
        poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void poll() {
        new Thread(() -> {
            final JSONObject status = fetch();
            handler.post(() -> {
                if (!resumed) return;
                render(status);
                handler.postDelayed(this::poll, 1500);
            });
        }, "status").start();
    }

    private static JSONObject fetch() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", STATUS_PORT), 500);
            s.setSoTimeout(1000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void render(JSONObject st) {
        if (st != null) {
            reconnectTried = false;
            // The helper predates this version of the app (it was just updated): restart it once.
            if (prefs.isPaired() && !reconnecting && !refreshTried && !Reconnector.isCurrent(this, st)) {
                refreshTried = true;
                reconnect();
                return;
            }
            String mac = st.optString("mac", "your Mac");
            action.setVisibility(android.view.View.GONE);
            if (st.optBoolean("connected")) {
                headline.setText("Connected to " + mac);
                detail.setText("Copy on either device, paste on the other. Text, images and files all sync.\n\n"
                        + "You can close this app — syncing runs in the background.");
            } else {
                headline.setText("Looking for " + mac + "…");
                detail.setText("The helper is running but can’t reach your Mac. Check that both devices are on the "
                        + "same Wi‑Fi and that ClipBridge is running on the Mac.");
            }
            long at = st.optLong("lastSyncAt", 0);
            last.setText(at > 0
                    ? "Last: " + st.optString("lastSync") + ", "
                    + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                    : "Nothing synced yet since the helper started.");
            return;
        }

        // Helper is not running.
        last.setText("");
        if (!prefs.isPaired()) {
            headline.setText("Finish setting up");
            detail.setText("ClipBridge needs a one-time setup to sync in the background — about a minute, no computer.");
            action.setText("Set up ClipBridge");
            action.setEnabled(true);
            action.setVisibility(android.view.View.VISIBLE);
            action.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
            return;
        }

        if (reconnecting) {
            headline.setText("Reconnecting…");
            detail.setText("Starting the background helper again.");
            action.setVisibility(android.view.View.GONE);
            return;
        }

        headline.setText("Not running");
        detail.setText("The helper stops when the phone restarts. Make sure Wireless debugging is on, then reconnect — "
                + "no need to pair again.");
        action.setText("Reconnect");
        action.setEnabled(true);
        action.setVisibility(android.view.View.VISIBLE);
        action.setOnClickListener(v -> reconnect());

        // Try once automatically on entry.
        if (!reconnectTried) {
            reconnectTried = true;
            reconnect();
        }
    }

    private void reconnect() {
        if (reconnecting) return;
        reconnecting = true;
        action.setVisibility(android.view.View.GONE);
        headline.setText("Reconnecting…");
        detail.setText("Starting the background helper again.");
        new Thread(() -> {
            boolean ok = Reconnector.ensureRunning(this,
                    m -> android.util.Log.i("ClipBridge", "reconnect: " + m));
            final String fError = ok ? null : "helper did not start";
            handler.post(() -> {
                reconnecting = false;
                if (!resumed) return;
                if (fError != null) {
                    headline.setText("Couldn’t reconnect");
                    detail.setText("Turn on Wireless debugging (Developer options), then tap Reconnect. "
                            + "If it keeps failing, re-run setup.");
                    action.setText("Reconnect");
                    action.setVisibility(android.view.View.VISIBLE);
                    action.setOnClickListener(v -> reconnect());
                }
                // On success the next poll sees the helper and switches to the connected view.
            });
        }, "reconnect").start();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
