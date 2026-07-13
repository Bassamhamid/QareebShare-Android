package com.bassam.qareebshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class ReceivedFileStore {
    interface Target {
        OutputStream outputStream();

        void commit() throws IOException;

        void abort();

        String displayLocation();
    }

    private ReceivedFileStore() {
    }

    static Target create(Context context, TransferItemInfo item) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return createMediaStoreTarget(context, item);
        }
        return createLegacyTarget(item);
    }

    private static Target createMediaStoreTarget(Context context, TransferItemInfo item) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/QareebShare";
        if (item.kind == TransferItemInfo.KIND_APP) {
            relativePath += "/Apps";
            if (!item.groupId.isEmpty()) {
                relativePath += "/" + FileNameSanitizer.sanitize(item.groupId, "Application");
            }
        }

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
        OutputStream output = resolver.openOutputStream(uri, "w");
        if (output == null) {
            resolver.delete(uri, null, null);
            throw new IOException("Unable to open received file");
        }

        String finalRelativePath = relativePath;
        return new Target() {
            private boolean finished;

            @Override
            public OutputStream outputStream() {
                return output;
            }

            @Override
            public void commit() throws IOException {
                if (finished) {
                    return;
                }
                output.flush();
                output.close();
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                int updated = resolver.update(uri, complete, null, null);
                if (updated <= 0) {
                    throw new IOException("Unable to finalize received file");
                }
                finished = true;
            }

            @Override
            public void abort() {
                if (finished) {
                    return;
                }
                finished = true;
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }

            @Override
            public String displayLocation() {
                return finalRelativePath;
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static Target createLegacyTarget(TransferItemInfo item) throws IOException {
        String relativeDirectory = "QareebShare";
        if (item.kind == TransferItemInfo.KIND_APP) {
            relativeDirectory += "/Apps";
            if (!item.groupId.isEmpty()) {
                relativeDirectory += "/" + FileNameSanitizer.sanitize(item.groupId, "Application");
            }
        }
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                relativeDirectory
        );
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create downloads directory");
        }

        File outputFile = uniqueFile(directory,
                FileNameSanitizer.sanitize(item.displayName, "ملف"));
        File temporaryFile = uniqueFile(directory, outputFile.getName() + ".part");
        FileOutputStream output = new FileOutputStream(temporaryFile);
        return new Target() {
            private boolean finished;

            @Override
            public OutputStream outputStream() {
                return output;
            }

            @Override
            public void commit() throws IOException {
                if (finished) {
                    return;
                }
                output.flush();
                output.getFD().sync();
                output.close();
                if (!temporaryFile.renameTo(outputFile)) {
                    temporaryFile.delete();
                    throw new IOException("Unable to finalize received file");
                }
                finished = true;
            }

            @Override
            public void abort() {
                if (finished) {
                    return;
                }
                finished = true;
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                if (!temporaryFile.delete()) {
                    temporaryFile.deleteOnExit();
                }
            }

            @Override
            public String displayLocation() {
                return outputFile.getParent();
            }
        };
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
}
