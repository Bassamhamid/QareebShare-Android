package com.bassam.qareebshare;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

final class NearbyPermissionManager {
    static final int REQUEST_CODE = 2402;
    private static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    private NearbyPermissionManager() {
    }

    static boolean hasRequiredPermissions(Activity activity) {
        return missingPermissions(activity).length == 0;
    }

    static void requestMissingPermissions(Activity activity) {
        String[] missing = missingPermissions(activity);
        if (missing.length > 0) {
            activity.requestPermissions(missing, REQUEST_CODE);
        }
    }

    static boolean isLegacyLocationEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }

        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String[] missingPermissions(Activity activity) {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(activity, permissions, Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            addIfMissing(activity, permissions, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 37) {
            addIfMissing(activity, permissions, ACCESS_LOCAL_NETWORK);
        }
        return permissions.toArray(new String[0]);
    }

    private static void addIfMissing(Activity activity, List<String> target, String permission) {
        if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            target.add(permission);
        }
    }
}
