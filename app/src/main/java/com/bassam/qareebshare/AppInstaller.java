package com.bassam.qareebshare;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppInstaller {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppInstaller() {
    }

    static boolean ensurePermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        PackageManager packageManager = activity.getPackageManager();
        if (packageManager.canRequestPackageInstalls()) {
            return true;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(activity, R.string.install_permission_unavailable, Toast.LENGTH_LONG).show();
        }
        return false;
    }

    static void install(Activity activity, TransferHistoryStore.AppPackage appPackage) {
        if (appPackage == null || appPackage.parts.isEmpty()) {
            Toast.makeText(activity, R.string.install_files_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensurePermission(activity)) {
            Toast.makeText(activity, R.string.allow_install_then_retry, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.preparing_app_install, Toast.LENGTH_SHORT).show();
        Context appContext = activity.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                installInBackground(appContext, appPackage);
            } catch (Exception error) {
                activity.runOnUiThread(() -> Toast.makeText(
                        activity,
                        R.string.install_failed,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private static void installInBackground(
            Context context,
            TransferHistoryStore.AppPackage appPackage
    ) throws Exception {
        ArrayList<TransferHistoryStore.StoredItem> parts = new ArrayList<>(appPackage.parts);
        Collections.sort(parts, Comparator.comparingInt(AppInstaller::partOrder));
        long totalSize = 0L;
        boolean hasBase = false;
        for (TransferHistoryStore.StoredItem item : parts) {
            totalSize += Math.max(0L, item.size);
            if (isBase(item)) {
                hasBase = true;
            }
        }
        if (!hasBase) {
            throw new IOException("Base APK is missing");
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
        );
        if (!appPackage.packageName.isEmpty()) {
            params.setAppPackageName(appPackage.packageName);
        }
        if (totalSize > 0L) {
            params.setSize(totalSize);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            for (int index = 0; index < parts.size(); index++) {
                TransferHistoryStore.StoredItem item = parts.get(index);
                String entryName = entryName(item, index);
                try (InputStream input = openInput(context, item);
                     OutputStream output = session.openWrite(entryName, 0L, Math.max(0L, item.size))) {
                    if (input == null) {
                        throw new IOException("Unable to read APK part");
                    }
                    copy(input, output);
                    session.fsync(output);
                }
            }

            Intent resultIntent = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(InstallResultReceiver.EXTRA_APP_LABEL, appPackage.label)
                    .putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, appPackage.packageName);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    resultIntent,
                    flags
            );
            IntentSender sender = pendingIntent.getIntentSender();
            session.commit(sender);
            committed = true;
        } finally {
            session.close();
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static InputStream openInput(
            Context context,
            TransferHistoryStore.StoredItem item
    ) throws IOException {
        if (!item.uri.isEmpty()) {
            return context.getContentResolver().openInputStream(Uri.parse(item.uri));
        }
        if (!item.filePath.isEmpty()) {
            File file = new File(item.filePath);
            if (file.isFile()) {
                return new FileInputStream(file);
            }
        }
        throw new IOException("Stored APK part is missing");
    }

    private static int partOrder(TransferHistoryStore.StoredItem item) {
        return isBase(item) ? 0 : 1;
    }

    private static boolean isBase(TransferHistoryStore.StoredItem item) {
        String part = item.partName == null ? "" : item.partName.toLowerCase(java.util.Locale.US);
        return part.equals("base") || part.equals("base.apk") || part.startsWith("base-");
    }

    private static String entryName(TransferHistoryStore.StoredItem item, int index) {
        if (isBase(item)) {
            return "base.apk";
        }
        String name = FileNameSanitizer.sanitize(item.partName, "split-" + index + ".apk");
        if (!name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
            name += ".apk";
        }
        return name;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
