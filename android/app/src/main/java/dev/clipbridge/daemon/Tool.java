package dev.clipbridge.daemon;

import android.content.ClipData;
import android.content.ClipDescription;

import java.nio.charset.StandardCharsets;

/**
 * Diagnostics, run as shell:
 *   CLASSPATH=$(pm path dev.clipbridge | cut -d: -f2) app_process / dev.clipbridge.daemon.Tool get
 * Prints what is on the clipboard (types, sizes and hashes — never the contents).
 */
public final class Tool {
    public static void main(String[] args) throws Exception {
        ShellClipboard cb = new ShellClipboard();
        String cmd = args.length > 0 ? args[0] : "get";
        if (cmd.equals("set") && args.length > 1) {
            System.out.println(cb.set(ClipData.newPlainText("tool", args[1])) ? "ok" : "failed");
            return;
        }
        ClipData cd = cb.get();
        if (cd == null) {
            System.out.println("clipboard: empty, or phone locked");
            return;
        }
        ClipDescription d = cd.getDescription();
        StringBuilder sb = new StringBuilder("mimes=");
        for (int i = 0; i < d.getMimeTypeCount(); i++) sb.append(i > 0 ? "," : "").append(d.getMimeType(i));
        if (d.getExtras() != null) sb.append(" extras=").append(d.getExtras().keySet());
        for (int i = 0; i < cd.getItemCount(); i++) {
            ClipData.Item it = cd.getItemAt(i);
            sb.append("\nitem ").append(i).append(':');
            if (it.getText() != null) {
                byte[] b = it.getText().toString().getBytes(StandardCharsets.UTF_8);
                sb.append(" text len=").append(b.length).append(" sha256=").append(Io.sha256(b), 0, 16);
            }
            if (it.getHtmlText() != null) sb.append(" html len=").append(it.getHtmlText().length());
            if (it.getUri() != null) sb.append(" uri=").append(it.getUri());
        }
        System.out.println(sb);
    }
}
