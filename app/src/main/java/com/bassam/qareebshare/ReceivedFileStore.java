package com.bassam.qareebshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

final class ReceivedFileStore {
    static final class StoredLocation {
        final String uri;
        final String filePath;
        final String displayLocation;

        StoredLocation(String uri, String filePath, String displayLocation) {
            this.uri = uri == null ? "" : uri;
            this.filePath = filePath == null ? "" : filePath;
            this.displayLocation = displayLocation == null ? "" : displayLocation;
        }
    }

    static final class Partial {
        private final File file;
        private final RandomAccessFile output;
        private boolean closed;

        Partial(File file, RandomAccessFile output) {
            this.file = file;
            this.output = output;
        }

        long offset() throws IOException {
            return output.getFilePointer();
        }

        void write(byte[] data, int offset, int length) throws IOException {
            output.write(data, offset, length);
        }

        File file() {
            return file;
        }

        void finishWriting() throws IOException {
            if (closed) {
                return;
            }
            output.getFD().sync();
            output.close();
            closed = true;
            file.setLastModified(System.currentTimeMillis());
        }

        void closeKeep() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                output.close();
            } catch (IOException ignored) {
            }
            file.setLastModified(System.currentTimeMillis());
        }

        void discard() {
            closeKeep();
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    private static final long STALE_PART_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    private ReceivedFileStore() {
    }

    static long resumeOffset(Context context, String transferId, TransferItemInfo item) {
        if (item.size < 0L) {
            return 0L;
        }
        File file = partialFile(context, transferId, item.id);
        if (!file.isFile()) {
            return 0L;
        }
        long length = file.length();
        if (length < 0L || length > item.size) {
            if (!file.delete()) {
                file.deleteOnExit();
            }
            return 0L;
        }
        return length;
    }

    static Partial openPartial(
            Context context,
            String transferId,
            TransferItemInfo item,
            long requestedOffset
    ) throws IOException {
        File file = partialFile(context, transferId, item.id);
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Unable to create partial transfer directory");
        }

        RandomAccessFile output = new RandomAccessFile(file, "rw");
        long actualOffset;
        if (item.size < 0L) {
            output.setLength(0L);
            actualOffset = 0L;
        } else {
            actualOffset = Math.max(0L, Math.min(requestedOffset, output.length()));
            if (actualOffset != requestedOffset) {
                output.setLength(actualOffset);
            }
        }
        output.seek(actualOffset);
        file.setLastModified(System.currentTimeMillis());
        return new Partial(file, output);
    }

    static StoredLocation publish(
            Context context,
            TransferItemInfo item,
            Partial partial
    ) throws IOException {
        partial.finishWriting();
        StoredLocation location = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? publishMediaStore(context, item, partial.file())
                : publishLegacy(item, partial.file());
        if (!partial.file().delete()) {
            partial.file().deleteOnExit();
        }
        removeEmptyParents(context, partial.file().getParentFile());
        return location;
    }

    static void cleanupStale(Context context) {
        File root = new File(context.getFilesDir(), "incoming");
        long cutoff = System.currentTimeMillis() - STALE_PART_AGE_MS;
        deleteStale(root, cutoff);
    }

    @android.annotation.SuppressLint("NewApi")
    private static StoredLocation publishMediaStore(
            Context context,
            TransferItemInfo item,
            File source
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = relativePath(item);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                FileNameSanitizer.sanitize(item.displayName, "ملف"));
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                item.mimeType.isEmpty() ? "application/octet-stream" : item.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Unable to create received file");
        }
        boolean committed = false;
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("Unable to open received file");
            }
            copy(input, output);
            output.flush();
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, complete, null, null) <= 0) {
                throw new IOException("Unable to finalize received file");
            }
            committed = true;
            return new StoredLocation(uri.toString(), "", relativePath);
        } finally {
            if (!committed) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static StoredLocation publishLegacy(TransferItemInfo item, File source) throws IOException {
        String relativeDirectory = legacyRelativeDirectory(item);
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                relativeDirectory
        );
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create downloads directory");
        }
        File target = uniqueFile(directory,
                FileNameSanitizer.sanitize(item.displayName, "ملف"));
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
            output.flush();
            output.getFD().sync();
        }
        return new StoredLocation("", target.getAbsolutePath(), target.getParent());
    }

    private static String relativePath(TransferItemInfo item) {
        String path = Environment.DIRECTORY_DOWNLOADS + "/QareebShare";
        if (item.kind == TransferItemInfo.KIND_APP) {
            path += "/Apps";
            String group = item.packageName.isEmpty() ? item.groupId : item.packageName;
            if (!group.isEmpty()) {
                path += "/" + FileNameSanitizer.sanitize(group, "Application");
            }
        }
        return path;
    }

    private static String legacyRelativeDirectory(TransferItemInfo item) {
        String path = "QareebShare";
        if (item.kind == TransferItemInfo.KIND_APP) {
            path += "/Apps";
            String group = item.packageName.isEmpty() ? item.groupId : item.packageName;
            if (!group.isEmpty()) {
                path += "/" + FileNameSanitizer.sanitize(group, "Application");
            }
        }
        return path;
    }

    private static File partialFile(Context context, String transferId, String itemId) {
        String safeTransfer = safeIdentifier(transferId, "transfer");
        String safeItem = safeIdentifier(itemId, "item");
        return new File(new File(context.getFilesDir(), "incoming/" + safeTransfer), safeItem + ".part");
    }

    private static String safeIdentifier(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static void copy(FileInputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 10_000; index++) {
            candidate = new File(directory, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, System.currentTimeMillis() + "-" + name);
    }

    private static void deleteStale(File file, long cutoff) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteStale(child, cutoff);
                }
            }
            children = file.listFiles();
            if (children != null && children.length == 0) {
                file.delete();
            }
            return;
        }
        if (file.lastModified() < cutoff && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static void removeEmptyParents(Context context, File directory) {
        File stop = new File(context.getFilesDir(), "incoming");
        File current = directory;
        while (current != null && !current.equals(stop)) {
            File[] children = current.listFiles();
            if (children != null && children.length == 0) {
                current.delete();
                current = current.getParentFile();
            } else {
                break;
            }
        }
    }
}
