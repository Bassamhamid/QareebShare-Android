package com.bassam.qareebshare;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

final class StoragePermissionManager {
    static final int REQUEST_CODE = 2403;

    private StoragePermissionManager() {
    }

    static boolean hasReceivePermission(Activity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void requestReceivePermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE
            );
        }
    }
}
