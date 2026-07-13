package com.bassam.qareebshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

public final class SessionActivity extends Activity implements SessionController.Listener {
    private static final int REQUEST_NEARBY = 4101;
    private static final String ACCESS_LOCAL_NETWORK =
            "android.permission.ACCESS_LOCAL_NETWORK";

    private enum PendingAction {
        NONE,
        HOST,
        JOIN
    }

    private SessionController controller;
    private SessionPeerAdapter peerAdapter;
    private SessionController.State renderedState = SessionController.State.IDLE;
    private PendingAction pendingAction = PendingAction.NONE;

    private View rolePanel;
    private Button createButton;
    private Button joinButton;
    private View statusPanel;
    private TextView statusTitle;
    private TextView statusBody;
    private ProgressBar progress;
    private Button primaryButton;
    private View devicesPanel;
    private TextView emptyDevices;
    private ListView devicesList;
    private View connectedPanel;
    private TextView connectedName;
    private Button disconnectButton;

    private boolean continueAfterSettings;
    private boolean continueAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session);
        configureSystemBars();
        registerBackHandler();
        bindViews();

        controller = new SessionController(this, this);
        peerAdapter = new SessionPeerAdapter(this);
        devicesList.setAdapter(peerAdapter);
        devicesList.setOnItemClickListener((parent, view, position, id) -> {
            if (controller.getState() != SessionController.State.JOIN_SEARCHING) {
                return;
            }
            PeerDevice peer = peerAdapter.getItem(position);
            controller.join(peer);
        });

        createButton.setOnClickListener(v -> begin(PendingAction.HOST));
        joinButton.setOnClickListener(v -> begin(PendingAction.JOIN));
        primaryButton.setOnClickListener(v -> onPrimaryAction());
        disconnectButton.setOnClickListener(v -> controller.disconnect());
        findViewById(R.id.button_theme).setOnClickListener(v -> showThemeChooser());
        renderState(SessionController.State.IDLE, "");
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.register();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller == null) {
            return;
        }
        controller.refreshWifiState();
        if (continueAfterSettings) {
            continueAfterSettings = false;
            continuePreparation();
        }
    }

    @Override
    protected void onStop() {
        controller.unregister();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.shutdown();
        }
        super.onDestroy();
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackAction
            );
        }
    }

    private void handleBackAction() {
        if (controller != null && controller.getState() != SessionController.State.IDLE
                && controller.getState() != SessionController.State.NEEDS_WIFI) {
            controller.cancelCurrentAction();
            pendingAction = PendingAction.NONE;
            return;
        }
        finish();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    private void bindViews() {
        rolePanel = findViewById(R.id.panel_role_choices);
        createButton = findViewById(R.id.button_create_session);
        joinButton = findViewById(R.id.button_join_session);
        statusPanel = findViewById(R.id.panel_status);
        statusTitle = findViewById(R.id.text_status_title);
        statusBody = findViewById(R.id.text_status_body);
        progress = findViewById(R.id.progress_session);
        primaryButton = findViewById(R.id.button_primary);
        devicesPanel = findViewById(R.id.panel_devices);
        emptyDevices = findViewById(R.id.text_devices_empty);
        devicesList = findViewById(R.id.list_devices);
        connectedPanel = findViewById(R.id.panel_connected);
        connectedName = findViewById(R.id.text_connected_name);
        disconnectButton = findViewById(R.id.button_disconnect);
    }

    private void begin(PendingAction action) {
        pendingAction = action;
        continuePreparation();
    }

    private void onPrimaryAction() {
        SessionController.State state = controller.getState();
        if (state == SessionController.State.NEEDS_WIFI) {
            openWifiPanel();
            return;
        }
        if (state == SessionController.State.ERROR) {
            if (pendingAction == PendingAction.NONE) {
                renderState(SessionController.State.IDLE, "");
            } else {
                continuePreparation();
            }
            return;
        }
        if (state == SessionController.State.HOST_PREPARING
                || state == SessionController.State.HOST_WAITING
                || state == SessionController.State.JOIN_PREPARING
                || state == SessionController.State.JOIN_SEARCHING
                || state == SessionController.State.JOIN_CONNECTING
                || state == SessionController.State.HANDSHAKING) {
            pendingAction = PendingAction.NONE;
            controller.cancelCurrentAction();
        }
    }

    private void continuePreparation() {
        if (pendingAction == PendingAction.NONE) {
            return;
        }
        if (!controller.isWifiEnabled()) {
            renderState(SessionController.State.NEEDS_WIFI, "");
            return;
        }
        if (!hasNearbyPermissions()) {
            continueAfterPermission = true;
            requestPermissions(requiredPermissions(), REQUEST_NEARBY);
            return;
        }
        if (!isLegacyLocationReady()) {
            showLocationRequired();
            return;
        }
        if (pendingAction == PendingAction.HOST) {
            controller.createSession();
        } else if (pendingAction == PendingAction.JOIN) {
            controller.findSessions();
        }
    }

    private boolean hasNearbyPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 37) {
            return new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    ACCESS_LOCAL_NETWORK
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
    }

    private boolean isLegacyLocationReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void openWifiPanel() {
        continueAfterSettings = true;
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(Settings.Panel.ACTION_WIFI);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showLocationRequired() {
        statusPanel.setVisibility(View.VISIBLE);
        rolePanel.setVisibility(View.GONE);
        statusTitle.setText(R.string.session_location_title);
        statusBody.setText(R.string.session_location_body);
        progress.setVisibility(View.GONE);
        primaryButton.setVisibility(View.VISIBLE);
        primaryButton.setText(R.string.session_open_location);
        primaryButton.setOnClickListener(v -> {
            continueAfterSettings = true;
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (ActivityNotFoundException error) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
            primaryButton.setOnClickListener(button -> onPrimaryAction());
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NEARBY) {
            return;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted && continueAfterPermission) {
            continueAfterPermission = false;
            continuePreparation();
        } else {
            continueAfterPermission = false;
            rolePanel.setVisibility(View.GONE);
            statusPanel.setVisibility(View.VISIBLE);
            statusTitle.setText(R.string.session_permission_title);
            statusBody.setText(R.string.session_permission_body);
            progress.setVisibility(View.GONE);
            primaryButton.setVisibility(View.VISIBLE);
            primaryButton.setText(R.string.session_try_again_button);
            primaryButton.setOnClickListener(v -> continuePreparation());
        }
    }

    private void showThemeChooser() {
        int selected = ThemeMode.get(this);
        String[] choices = getResources().getStringArray(R.array.session_theme_choices);
        new AlertDialog.Builder(this)
                .setTitle(R.string.session_theme_title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    ThemeMode.save(this, which);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton(R.string.session_cancel, null)
                .show();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(getColor(R.color.session_background));
        window.setNavigationBarColor(getColor(R.color.session_background));
        int night = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    @Override
    public void onStateChanged(SessionController.State state, String peerName) {
        runOnUiThread(() -> renderState(state, peerName));
    }

    @Override
    public void onPeersChanged(List<PeerDevice> peers) {
        runOnUiThread(() -> {
            List<PeerDevice> safe = peers == null ? Collections.emptyList() : peers;
            peerAdapter.replace(safe);
            boolean empty = safe.isEmpty();
            emptyDevices.setVisibility(empty ? View.VISIBLE : View.GONE);
            devicesList.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    @Override
    public void onFriendlyMessage(int messageResId) {
        runOnUiThread(() -> {
            statusPanel.setVisibility(View.VISIBLE);
            statusTitle.setText(messageResId);
            if (renderedState == SessionController.State.HOST_WAITING) {
                statusBody.setText(R.string.session_host_waiting_body);
            } else {
                statusBody.setText(R.string.session_retry_body);
            }
        });
    }

    @Override
    public void onWifiRequired() {
        runOnUiThread(() -> renderState(SessionController.State.NEEDS_WIFI, ""));
    }

    private void renderState(SessionController.State state, String peerName) {
        renderedState = state;
        primaryButton.setOnClickListener(v -> onPrimaryAction());
        rolePanel.setVisibility(View.GONE);
        statusPanel.setVisibility(View.VISIBLE);
        connectedPanel.setVisibility(View.GONE);
        devicesPanel.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        primaryButton.setVisibility(View.GONE);
        primaryButton.setEnabled(true);

        switch (state) {
            case NEEDS_WIFI:
                statusTitle.setText(R.string.session_wifi_off_title);
                statusBody.setText(R.string.session_wifi_off_body);
                primaryButton.setVisibility(View.VISIBLE);
                primaryButton.setText(R.string.session_turn_on_wifi);
                break;
            case HOST_PREPARING:
                statusTitle.setText(R.string.session_host_preparing_title);
                statusBody.setText(R.string.session_host_preparing_body);
                progress.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case HOST_WAITING:
                statusTitle.setText(R.string.session_host_waiting_title);
                statusBody.setText(R.string.session_host_waiting_body);
                progress.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case JOIN_PREPARING:
                statusTitle.setText(R.string.session_join_preparing_title);
                statusBody.setText(R.string.session_join_preparing_body);
                progress.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case JOIN_SEARCHING:
                statusTitle.setText(R.string.session_join_searching_title);
                statusBody.setText(R.string.session_join_searching_body);
                progress.setVisibility(View.VISIBLE);
                devicesPanel.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case JOIN_CONNECTING:
                statusTitle.setText(R.string.session_join_connecting_title);
                statusBody.setText(getString(
                        R.string.session_join_connecting_body,
                        safePeerName(peerName)
                ));
                progress.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case HANDSHAKING:
                statusTitle.setText(R.string.session_finishing_connection_title);
                statusBody.setText(R.string.session_finishing_connection_body);
                progress.setVisibility(View.VISIBLE);
                showCancelButton();
                break;
            case CONNECTED:
                statusPanel.setVisibility(View.GONE);
                connectedPanel.setVisibility(View.VISIBLE);
                connectedName.setText(safePeerName(peerName));
                break;
            case DISCONNECTING:
                statusTitle.setText(R.string.session_disconnecting_title);
                statusBody.setText(R.string.session_disconnecting_body);
                progress.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                statusTitle.setText(R.string.session_connection_failed);
                statusBody.setText(R.string.session_retry_body);
                primaryButton.setVisibility(View.VISIBLE);
                primaryButton.setText(pendingAction == PendingAction.NONE
                        ? R.string.session_back_to_start
                        : R.string.session_try_again_button);
                break;
            case IDLE:
            default:
                if (continueAfterSettings || continueAfterPermission) {
                    statusTitle.setText(R.string.session_join_preparing_title);
                    statusBody.setText(R.string.session_join_preparing_body);
                    progress.setVisibility(View.VISIBLE);
                } else {
                    pendingAction = PendingAction.NONE;
                    statusPanel.setVisibility(View.GONE);
                    rolePanel.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    private void showCancelButton() {
        primaryButton.setVisibility(View.VISIBLE);
        primaryButton.setText(R.string.session_cancel_action);
    }

    private String safePeerName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.session_nearby_phone);
        }
        return value.trim();
    }
}
