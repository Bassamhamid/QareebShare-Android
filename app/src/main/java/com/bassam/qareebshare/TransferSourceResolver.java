package com.bassam.qareebshare;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    static String transferId(List<TransferSource> sources) {
        StringBuilder builder = new StringBuilder("QSH4|");
        for (TransferSource source : sources) {
            TransferItemInfo item = source.info;
            builder.append(item.id).append('|')
                    .append(item.size).append('|')
                    .append(item.kind).append('|')
                    .append(item.packageName).append('|')
                    .append(item.partName).append(';');
        }
        return stableId(builder.toString());
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
        String id = stableId("document|" + uri + "|" + safeName + "|" + size);
        TransferItemInfo info = new TransferItemInfo(
                id,
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

            PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
            String versionName = packageInfo.versionName == null ? "" : packageInfo.versionName.trim();
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
            String stem = versionName.isEmpty() ? label : label + "-" + versionName;

            addApkPart(
                    target,
                    applicationInfo.sourceDir,
                    stem + "-base.apk",
                    packageName,
                    versionCode,
                    "base.apk"
            );

            String[] splitSourceDirs = applicationInfo.splitSourceDirs;
            if (splitSourceDirs != null) {
                for (int index = 0; index < splitSourceDirs.length; index++) {
                    String sourcePath = splitSourceDirs[index];
                    String originalName = new File(sourcePath).getName();
                    String part = FileNameSanitizer.sanitize(
                            originalName,
                            "split-" + (index + 1) + ".apk"
                    );
                    String outputName = stem + "-" + part;
                    addApkPart(
                            target,
                            sourcePath,
                            outputName,
                            packageName,
                            versionCode,
                            part
                    );
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
            long versionCode,
            String partName
    ) throws IOException {
        File file = new File(sourcePath == null ? "" : sourcePath);
        if (!file.isFile() || !file.canRead()) {
            throw new IOException("Application package is not readable");
        }
        String id = stableId(
                "application|" + packageName + "|" + versionCode + "|"
                        + partName + "|" + file.length()
        );
        TransferItemInfo info = new TransferItemInfo(
                id,
                FileNameSanitizer.sanitize(displayName, "application.apk"),
                "application/vnd.android.package-archive",
                file.length(),
                TransferItemInfo.KIND_APP,
                packageName,
                packageName,
                partName
        );
        target.add(new TransferSource(info, () -> new FileInputStream(file)));
    }

    private static String stableId(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(40);
            for (int index = 0; index < 20; index++) {
                result.append(String.format(java.util.Locale.US, "%02x", hash[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
