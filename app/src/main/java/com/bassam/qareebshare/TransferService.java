package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

@SuppressLint("MissingPermission")
public final class TransferService extends Service {
    private static final String CHANNEL_ID = "qareeb_transfer";
    private static final int NOTIFICATION_ID = 3107;
    private static final String ACTION_START = "com.bassam.qareebshare.transfer.START";
    private static final String ACTION_UPDATE = "com.bassam.qareebshare.transfer.UPDATE";
    private static final String ACTION_STOP = "com.bassam.qareebshare.transfer.STOP";
    private static final String ACTION_CANCEL = "com.bassam.qareebshare.transfer.CANCEL";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_PROGRESS = "progress";
    private static final String EXTRA_INDETERMINATE = "indeterminate";

    private boolean foregroundStarted;

    static void start(Context context, String title, String text, boolean indeterminate) {
        startCompat(context, command(context, ACTION_START, title, text, 0, indeterminate));
    }

    static void update(
            Context context,
            String title,
            String text,
            int progress,
            boolean indeterminate
    ) {
        startCompat(context, command(
                context,
                ACTION_UPDATE,
                title,
                text,
                progress,
                indeterminate
        ));
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, TransferService.class));
        } catch (RuntimeException ignored) {
        }
    }


    private static void startCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // File transfer remains functional even if the status service is blocked.
        }
    }

    private static Intent command(
            Context context,
            String action,
            String title,
            String text,
            int progress,
            boolean indeterminate
    ) {
        return new Intent(context, TransferService.class)
                .setAction(action)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_INDETERMINATE, indeterminate);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            TransferCancelRegistry.cancel();
            foregroundStarted = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            foregroundStarted = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String title = intent == null ? getString(R.string.transfer_in_progress)
                : intent.getStringExtra(EXTRA_TITLE);
        String text = intent == null ? "" : intent.getStringExtra(EXTRA_TEXT);
        int progress = intent == null ? 0 : intent.getIntExtra(EXTRA_PROGRESS, 0);
        boolean indeterminate = intent == null || intent.getBooleanExtra(EXTRA_INDETERMINATE, true);
        Notification notification = buildNotification(title, text, progress, indeterminate);

        if (ACTION_START.equals(action) || !foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(
            String title,
            String text,
            int progress,
            boolean indeterminate
    ) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        );

        Intent cancelIntent = new Intent(this, TransferService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this,
                2,
                cancelIntent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title == null || title.isEmpty()
                        ? getString(R.string.transfer_in_progress) : title)
                .setContentText(text == null ? "" : text)
                .setContentIntent(openPendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, Math.max(0, Math.min(100, progress)), indeterminate)
                .addAction(0, getString(R.string.cancel_transfer), cancelPendingIntent)
                .build();
    }

    private int pendingIntentFlags(int base) {
        return base | PendingIntent.FLAG_IMMUTABLE;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.transfer_channel_description));
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }
}
