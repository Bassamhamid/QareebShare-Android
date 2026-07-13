package com.bassam.qareebshare;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TransferSourceResolver {
    private TransferSourceResolver() {
    }

    static List<TransferSource> resolve(
            Context context,
            List<Uri> selectedFiles,
            Set<String> selectedPackages
    ) throws IOException {
        ArrayList<TransferSource> result = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();

        for (Uri uri : selectedFiles) {
            result.add(resolveDocument(resolver, uri));
        }

        PackageManager packageManager = context.getPackageManager();
        for (String packageName : selectedPackages) {
            resolveApplication(packageManager, packageName, result);
        }
        return result;
    }

    private static TransferSource resolveDocument(ContentResolver resolver, Uri uri) throws IOException {
        String name = null;
        long size = -1L;
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException ignored) {
        }

        String safeName = FileNameSanitizer.sanitize(name, "ملف");
        String mime = resolver.getType(uri);
        if (mime == null || mime.trim().isEmpty()) {
            mime = "application/octet-stream";
        }
        TransferItemInfo info = new TransferItemInfo(
                UUID.randomUUID().toString(),
                safeName,
                mime,
                size,
                TransferItemInfo.KIND_FILE,
                "",
                "",
                ""
        );
        return new TransferSource(info, () -> {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) {
                throw new IOException("Unable to open selected file");
            }
            return input;
        });
    }

    private static void resolveApplication(
            PackageManager packageManager,
            String packageName,
            List<TransferSource> target
    ) throws IOException {
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence labelSequence = packageManager.getApplicationLabel(applicationInfo);
            String label = labelSequence == null ? packageName : labelSequence.toString();
            label = FileNameSanitizer.sanitize(label, packageName);

            String versionName = "";
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
                if (packageInfo.versionName != null) {
                    versionName = packageInfo.versionName.trim();
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            String stem = versionName.isEmpty() ? label : label + "-" + versionName;
            addApkPart(target, applicationInfo.sourceDir, stem + ".apk", packageName, label, "base");

            String[] splitSourceDirs = applicationInfo.splitSourceDirs;
            if (splitSourceDirs != null) {
                for (int index = 0; index < splitSourceDirs.length; index++) {
                    String sourcePath = splitSourceDirs[index];
                    String originalName = new File(sourcePath).getName();
                    String part = FileNameSanitizer.sanitize(originalName, "split-" + (index + 1) + ".apk");
                    String outputName = stem + "-" + part;
                    addApkPart(target, sourcePath, outputName, packageName, label, part);
                }
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("Application not found: " + packageName, error);
        }
    }

    private static void addApkPart(
            List<TransferSource> target,
            String sourcePath,
            String displayName,
            String packageName,
            String appLabel,
            String partName
    ) throws IOException {
        File file = new File(sourcePath == null ? "" : sourcePath);
        if (!file.isFile() || !file.canRead()) {
            throw new IOException("Application package is not readable");
        }
        TransferItemInfo info = new TransferItemInfo(
                UUID.randomUUID().toString(),
                FileNameSanitizer.sanitize(displayName, "application.apk"),
                "application/vnd.android.package-archive",
                file.length(),
                TransferItemInfo.KIND_APP,
                appLabel,
                packageName,
                partName
        );
        target.add(new TransferSource(info, () -> new FileInputStream(file)));
    }
}
