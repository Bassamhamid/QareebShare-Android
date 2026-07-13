package com.bassam.qareebshare;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

final class ThemeMode {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private static final String PREFS = "appearance";
    private static final String KEY_MODE = "mode";

    private ThemeMode() {
    }

    static int get(Context context) {
        return preferences(context).getInt(KEY_MODE, SYSTEM);
    }

    static void save(Context context, int mode) {
        preferences(context).edit().putInt(KEY_MODE, mode).apply();
    }

    @SuppressWarnings("deprecation")
    static void apply(Activity activity) {
        int selected = get(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            UiModeManager manager = (UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE);
            if (manager != null) {
                int value;
                if (selected == LIGHT) {
                    value = UiModeManager.MODE_NIGHT_NO;
                } else if (selected == DARK) {
                    value = UiModeManager.MODE_NIGHT_YES;
                } else {
                    value = UiModeManager.MODE_NIGHT_AUTO;
                }
                manager.setApplicationNightMode(value);
            }
            return;
        }

        Resources resources = activity.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        int nightMask;
        if (selected == LIGHT) {
            nightMask = Configuration.UI_MODE_NIGHT_NO;
        } else if (selected == DARK) {
            nightMask = Configuration.UI_MODE_NIGHT_YES;
        } else {
            nightMask = Resources.getSystem().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            if (nightMask != Configuration.UI_MODE_NIGHT_YES) {
                nightMask = Configuration.UI_MODE_NIGHT_NO;
            }
        }
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | nightMask;
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
