package dev.clipbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import dev.clipbridge.adb.WirelessHelper;

/**
 * One-time wireless setup: turn on Wireless debugging, scan the Mac's pairing QR, then pair with a
 * six-digit code (entered into a notification so Android's pairing dialog stays in the foreground).
 */
public final class SetupActivity extends Activity implements PairingService.Observer {
    private static final int REQ_NOTIF = 7;
    private static final int REQ_SCAN = 8;

    private TextView status;
    private Button scanButton;
    private Button primary;
    private boolean pairingStarted;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        WirelessHelper.initCrypto();
        int pad = dp(24);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text(32, true);
        title.setText("Set up ClipBridge");
        col.addView(title);

        TextView intro = text(16, false);
        intro.setText("A one-time setup lets ClipBridge keep your clipboard in sync in the background. "
                + "It takes about a minute and needs no cable.");
        intro.setPadding(0, dp(10), 0, dp(20));
        col.addView(intro);

        col.addView(step("1", "Turn on Wireless debugging",
                "Opens Developer options. Switch on “Wireless debugging” and tap Allow."));
        Button openSettings = button("Open Developer options");
        openSettings.setOnClickListener(v -> openDevSettings());
        col.addView(openSettings);

        col.addView(step("2", "Scan the code on your Mac",
                "In ClipBridge on your Mac, click the menu-bar icon › “Pair a Phone”, then scan it here."));
        scanButton = button("Scan the Mac code");
        scanButton.setOnClickListener(v -> startScan());
        col.addView(scanButton);

        col.addView(step("3", "Start pairing here",
                "Tap below, then tap “Pair device with pairing code” in Settings. "
                        + "Swipe down and type the 6-digit code into the ClipBridge notification."));
        primary = button("Start pairing");
        primary.setOnClickListener(v -> startPairing());
        col.addView(primary);

        status = text(15, false);
        status.setPadding(0, dp(20), 0, 0);
        col.addView(status);

        TextView help = text(13, false);
        help.setAlpha(0.6f);
        help.setPadding(0, dp(28), 0, 0);
        help.setText("Developer options hidden? Open Settings › About phone and tap “Build number” "
                + "seven times, then come back.");
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        reflectScanState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PairingService.setObserver(this);
        reflectScanState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PairingService.setObserver(null);
    }

    private void reflectScanState() {
        if (prefs.macConfig() != null) {
            scanButton.setText("✓ Mac code scanned (tap to rescan)");
        }
    }

    private void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // scanning --------------------------------------------------------------

    private void startScan() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the QR code on your Mac")
                .setBeepEnabled(false)
                .setOrientationLocked(false);
        startActivityForResult(options.createScanIntent(this), REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN) return;
        ScanIntentResult result = ScanIntentResult.parseActivityResult(resultCode, data);
        if (result.getContents() == null) return; // cancelled
        onScanned(result.getContents());
    }

    private void onScanned(String contents) {
        String error = validateConfig(contents);
        if (error != null) {
            status.setText("That didn’t look like a ClipBridge code: " + error);
            return;
        }
        prefs.setMacConfig(contents);
        scanButton.setText("✓ Mac code scanned (tap to rescan)");
        status.setText("Mac code scanned. Now start pairing below.");
    }

    /** null if the scanned payload is a usable pairing config. */
    private static String validateConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            byte[] key = Base64.decode(o.getString("key"), Base64.DEFAULT);
            if (key.length < 32) return "key too short";
            int port = o.getInt("port");
            if (port <= 0 || port > 65535) return "bad port";
            return null;
        } catch (Throwable t) {
            return "not a pairing code";
        }
    }

    // pairing ---------------------------------------------------------------

    private void startPairing() {
        if (prefs.macConfig() == null) {
            status.setText("Scan the code on your Mac first (step 2).");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        launchPairing();
    }

    private void launchPairing() {
        pairingStarted = true;
        PairingService.start(this);
        status.setText("Now tap “Pair device with pairing code” in Settings, then type the code into the notification.");
        primary.setText("Pairing started — see the notification");
        primary.setEnabled(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIF) {
            launchPairing(); // proceed even if denied; the notification simply won't show
        }
    }

    @Override
    public void onPhase(PairingService.Phase phase, String detail) {
        if (!pairingStarted && phase == PairingService.Phase.IDLE) return;
        switch (phase) {
            case WAITING_FOR_DIALOG:
                status.setText("Waiting for the pairing dialog… open “Pair device with pairing code” in Settings.");
                break;
            case READY_FOR_CODE:
                status.setText("Found it. Swipe down and type the 6-digit code into the ClipBridge notification.");
                break;
            case PAIRING:
                status.setText("Pairing…");
                break;
            case SUCCESS:
                status.setText("✓ All set. ClipBridge is syncing in the background.");
                primary.setText("Done");
                finishToStatus();
                break;
            case FAILED:
                primary.setEnabled(true);
                primary.setText("Try again");
                status.setText("Pairing didn’t finish" + (detail != null ? ": " + detail : "") + ". Reopen the dialog and try again.");
                break;
            default:
                break;
        }
    }

    private void finishToStatus() {
        status.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }, 1500);
    }

    // small UI helpers ------------------------------------------------------

    private View step(String num, String heading, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(6));

        TextView badge = text(16, true);
        badge.setText(num);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setBackground(circle());
        int s = dp(30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(14);
        lp.topMargin = dp(2);
        badge.setLayoutParams(lp);
        row.addView(badge);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(18, true);
        h.setText(heading);
        TextView bgv = text(15, false);
        bgv.setText(body);
        bgv.setPadding(0, dp(2), 0, 0);
        bgv.setAlpha(0.85f);
        texts.addView(h);
        texts.addView(bgv);
        row.addView(texts);
        return row;
    }

    private android.graphics.drawable.GradientDrawable circle() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#0F766E"));
        return g;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(44);
        b.setLayoutParams(lp);
        return b;
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
