package dev.clipbridge;

import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * Holds images/files that arrived from the Mac and hands them to whatever app the user pastes
 * into. URIs look like content://dev.clipbridge.clips/<clip id>/<index>/<file name>?mime=<type>.
 *
 * The shell-user helper writes files in (via `content write`); everything else may only read.
 */
public final class ClipProvider extends ContentProvider {
    private static final int SHELL_UID = 2000;
    private static final int KEEP_CLIPS = 4;

    @Override
    public boolean onCreate() {
        return true;
    }

    private File root() {
        return new File(getContext().getFilesDir(), "clips");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 2 || !seg.get(0).matches("[0-9a-fA-F]{1,32}") || !seg.get(1).matches("[0-9]{1,3}")) {
            throw new FileNotFoundException("Unknown clip: " + uri);
        }
        return new File(new File(root(), seg.get(0)), seg.get(1));
    }

    private static String nameFor(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg.size() >= 3 ? seg.get(2) : "clipboard";
    }

    @Override
    public String getType(Uri uri) {
        String mime = uri.getQueryParameter("mime");
        if (mime != null && mime.contains("/")) return mime;
        String name = nameFor(uri);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (guess != null) return guess;
        }
        return "application/octet-stream";
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        String type = getType(uri);
        return ClipDescription.compareMimeTypes(type, mimeTypeFilter) ? new String[]{type} : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = nameFor(uri);
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (mode != null && (mode.contains("w") || mode.contains("t") || mode.contains("a"))) {
            int caller = Binder.getCallingUid();
            if (caller != SHELL_UID && caller != 0 && caller != Process.myUid()) {
                throw new SecurityException("ClipBridge clips are read-only");
            }
            File clipDir = f.getParentFile();
            clipDir.mkdirs();
            prune(clipDir);
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (!f.isFile()) throw new FileNotFoundException("Clip expired: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Keep only the most recent few clips on disk. */
    private void prune(File keep) {
        File[] dirs = root().listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= KEEP_CLIPS) return;
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int kept = 0;
        for (File d : dirs) {
            if (d.equals(keep) || kept < KEEP_CLIPS - 1) {
                if (!d.equals(keep)) kept++;
                continue;
            }
            File[] files = d.listFiles();
            if (files != null) for (File x : files) x.delete();
            d.delete();
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
