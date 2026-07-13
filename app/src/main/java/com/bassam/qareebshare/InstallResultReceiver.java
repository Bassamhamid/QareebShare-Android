package com.bassam.qareebshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class InstallResultReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_RESULT = "com.bassam.qareebshare.INSTALL_RESULT";
    static final String EXTRA_APP_LABEL = "app_label";
    static final String EXTRA_PACKAGE_NAME = "package_name";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmation);
                    return;
                } catch (RuntimeException ignored) {
                }
            }
            Toast.makeText(context, R.string.install_failed, Toast.LENGTH_LONG).show();
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, R.string.install_completed, Toast.LENGTH_LONG).show();
        } else {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (message == null || message.trim().isEmpty()) {
                message = context.getString(R.string.install_failed);
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
