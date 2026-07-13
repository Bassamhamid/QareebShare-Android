package com.bassam.qareebshare;

import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String packageName;
    final String label;
    final Drawable icon;
    final ApplicationInfo applicationInfo;

    AppEntry(String packageName, String label, Drawable icon, ApplicationInfo applicationInfo) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.applicationInfo = applicationInfo;
    }
}
