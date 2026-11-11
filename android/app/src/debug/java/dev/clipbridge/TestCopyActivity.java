package dev.clipbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.PersistableBundle;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Test hook (debug builds only). Copies something to the clipboard the way a normal app would:
 *   am start -n dev.clipbridge/.TestCopyActivity --es text "hello"
 *   am start -n dev.clipbridge/.TestCopyActivity --ez image true
 *   am start -n dev.clipbridge/.TestCopyActivity --ez file true
 * Add --ez sensitive true to flag the clip the way password managers do.
 */
public final class TestCopyActivity extends Activity {
    private boolean done;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done) return;
        done = true;
        try {
            Intent in = getIntent();
            ClipData clip;
            if (in.getBooleanExtra("image", false)) {
                clip = uriClip(makeImage(), "image/png");
            } else if (in.getBooleanExtra("file", false)) {
                clip = uriClip(makeFile(), "application/pdf");
            } else if (in.hasExtra("html")) {
                clip = ClipData.newHtmlText("test", in.getStringExtra("text"), in.getStringExtra("html"));
            } else {
                clip = ClipData.newPlainText("test", in.getStringExtra("text"));
            }
            if (in.getBooleanExtra("sensitive", false)) {
                PersistableBundle b = new PersistableBundle();
                b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(b);
            }
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        } catch (Exception e) {
            android.util.Log.e("ClipBridgeTest", "copy failed", e);
        }
        getWindow().getDecorView().postDelayed(this::finish, 400);
    }

    private ClipData uriClip(File f, String mime) {
        Uri uri = Uri.parse("content://dev.clipbridge.test/" + f.getName());
        return new ClipData(new ClipDescription("test", new String[]{mime}), new ClipData.Item(uri));
    }

    private File dir() {
        File d = new File(getFilesDir(), "test");
        d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        return d;
    }

    private File makeImage() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Random r = new Random();
        c.drawColor(Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(48);
        c.drawText("ClipBridge test " + System.currentTimeMillis() % 100000, 40, 210, p);
        File f = new File(dir(), "test-" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return f;
    }

    private File makeFile() throws Exception {
        File f = new File(dir(), "report-" + System.currentTimeMillis() % 100000 + ".pdf");
        byte[] junk = new byte[300_000];
        new Random().nextBytes(junk);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("%PDF-1.4 clipbridge test\n".getBytes());
            out.write(junk);
        }
        return f;
    }
}
