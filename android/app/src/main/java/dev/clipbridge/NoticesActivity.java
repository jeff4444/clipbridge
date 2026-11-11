package dev.clipbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Open-source licences. Shows legal/THIRD-PARTY-NOTICES.md followed by the full licence texts,
 * which the build packs into the app's assets (see app/build.gradle.kts).
 */
public final class NoticesActivity extends Activity {
    private static final String NOTICES = "THIRD-PARTY-NOTICES.md";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Open-source licences");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);

        TextView text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPadding(pad, pad, pad, pad);
        text.setText("Loading…");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        new Thread(() -> {
            final String all = load();
            runOnUiThread(() -> text.setText(all));
        }, "notices").start();
    }

    private String load() {
        StringBuilder sb = new StringBuilder();
        sb.append(read(NOTICES));
        try {
            String[] names = getAssets().list("");
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (!name.startsWith("LICENSE-") || !name.endsWith(".txt")) continue;
                    sb.append("\n\n────────────────────────────────────────\n");
                    sb.append(name.substring("LICENSE-".length(), name.length() - ".txt".length()).replace('-', ' '));
                    sb.append("\n────────────────────────────────────────\n\n");
                    sb.append(read(name));
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private String read(String asset) {
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(" + asset + " is missing from this build)\n";
        }
    }
}
