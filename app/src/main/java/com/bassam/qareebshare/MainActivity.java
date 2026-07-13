package com.bassam.qareebshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements WifiDirectEvents {
    private static final int REQUEST_PICK_FILES = 1001;

    private enum Screen {
        HOME,
        SEND,
        RECEIVE,
        APPS,
        HISTORY
    }

    private enum PendingNearbyAction {
        NONE,
        DISCOVER,
        RECEIVE
    }

    private final ArrayList<Uri> selectedFiles = new ArrayList<>();
    private final Set<String> selectedApps = new LinkedHashSet<>();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private Screen currentScreen = Screen.HOME;
    private PendingNearbyAction pendingNearbyAction = PendingNearbyAction.NONE;
    private WifiDirectController wifiDirectController;

    private TextView selectedFilesView;
    private TextView selectedAppsView;
    private AppsAdapter appsAdapter;
    private PeersAdapter peersAdapter;
    private TextView sendStatusView;
    private ProgressBar sendProgressView;
    private ListView peersListView;
    private TextView receiveStatusView;
    private TextView receivePeerView;
    private ProgressBar receiveProgressView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        wifiDirectController = new WifiDirectController(this, this);
        showHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        wifiDirectController.register();
    }

    @Override
    protected void onStop() {
        wifiDirectController.unregister();
        super.onStop();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(getColor(R.color.background));
        window.setNavigationBarColor(getColor(R.color.background));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (nightMode != Configuration.UI_MODE_NIGHT_YES) {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    private void showHome() {
        currentScreen = Screen.HOME;
        clearScreenReferences();
        setContentView(R.layout.activity_home);
        findViewById(R.id.card_send).setOnClickListener(v -> showSend());
        findViewById(R.id.card_receive).setOnClickListener(v -> showReceive());
        findViewById(R.id.card_history).setOnClickListener(v -> showHistory());
    }

    private void showSend() {
        currentScreen = Screen.SEND;
        clearScreenReferences();
        setContentView(R.layout.screen_send);
        bindBackButton();

        selectedFilesView = findViewById(R.id.text_selected_files);
        selectedAppsView = findViewById(R.id.text_selected_apps);
        sendStatusView = findViewById(R.id.text_send_status);
        sendProgressView = findViewById(R.id.progress_peers);
        peersListView = findViewById(R.id.list_peers);

        updateSelectedFilesText();
        updateSelectedAppsText();

        findViewById(R.id.card_files).setOnClickListener(v -> openFilePicker());
        findViewById(R.id.card_apps).setOnClickListener(v -> showApps());
        findViewById(R.id.button_find_devices).setOnClickListener(v -> beginDiscovery());

        peersAdapter = new PeersAdapter(this);
        peersAdapter.replace(wifiDirectController.currentPeers());
        peersListView.setAdapter(peersAdapter);
        peersListView.setOnItemClickListener((parent, view, position, id) -> {
            PeerDevice peer = peersAdapter.getItem(position);
            if (peer.isAvailable()) {
                wifiDirectController.connect(peer);
            }
        });
    }

    private void showReceive() {
        currentScreen = Screen.RECEIVE;
        clearScreenReferences();
        setContentView(R.layout.screen_receive);
        bindBackButton();

        receiveStatusView = findViewById(R.id.text_receive_status);
        receivePeerView = findViewById(R.id.text_receive_peer);
        receiveProgressView = findViewById(R.id.progress_receive);

        findViewById(R.id.button_start_receive).setOnClickListener(v -> {
            pendingNearbyAction = PendingNearbyAction.RECEIVE;
            runPendingNearbyAction();
        });
        findViewById(R.id.button_stop_receive).setOnClickListener(v -> {
            pendingNearbyAction = PendingNearbyAction.NONE;
            wifiDirectController.stopAll();
            setReceiveState(R.string.receive_stopped, false);
        });
    }

    private void showHistory() {
        currentScreen = Screen.HISTORY;
        clearScreenReferences();
        setContentView(R.layout.screen_history);
        bindBackButton();
    }

    private void showApps() {
        currentScreen = Screen.APPS;
        clearScreenReferences();
        setContentView(R.layout.screen_apps);
        bindBackButton();

        ProgressBar progress = findViewById(R.id.progress_apps);
        TextView state = findViewById(R.id.text_apps_state);
        TextView selected = findViewById(R.id.text_apps_selected);
        ListView list = findViewById(R.id.list_apps);

        appsAdapter = new AppsAdapter(this, selectedApps);
        list.setAdapter(appsAdapter);
        updateAppsSelectionText(selected);

        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry app = appsAdapter.getItem(position);
            if (!selectedApps.add(app.packageName)) {
                selectedApps.remove(app.packageName);
            }
            appsAdapter.notifyDataSetChanged();
            updateAppsSelectionText(selected);
        });

        selected.setOnClickListener(v -> showSend());

        backgroundExecutor.execute(() -> {
            List<AppEntry> installedApps = loadShareableApps();
            runOnUiThread(() -> {
                if (isFinishing() || currentScreen != Screen.APPS) {
                    return;
                }
                progress.setVisibility(View.GONE);
                if (installedApps.isEmpty()) {
                    state.setText(R.string.apps_empty);
                    state.setVisibility(View.VISIBLE);
                    list.setVisibility(View.GONE);
                } else {
                    state.setVisibility(View.GONE);
                    list.setVisibility(View.VISIBLE);
                    appsAdapter.replace(installedApps);
                }
            });
        });
    }

    private List<AppEntry> loadShareableApps() {
        PackageManager packageManager = getPackageManager();
        List<ApplicationInfo> applicationInfos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationInfos = packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0)
            );
        } else {
            applicationInfos = packageManager.getInstalledApplications(0);
        }

        ArrayList<AppEntry> result = new ArrayList<>();
        for (ApplicationInfo info : applicationInfos) {
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSystem = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (system || updatedSystem) {
                continue;
            }

            CharSequence labelSequence = packageManager.getApplicationLabel(info);
            String label = labelSequence == null ? info.packageName : labelSequence.toString();
            result.add(new AppEntry(
                    info.packageName,
                    label,
                    packageManager.getApplicationIcon(info),
                    info
            ));
        }

        Collator collator = Collator.getInstance(new Locale("ar"));
        Collections.sort(result, (left, right) -> collator.compare(left.label, right.label));
        return result;
    }

    private void beginDiscovery() {
        if (selectedFiles.isEmpty() && selectedApps.isEmpty()) {
            Toast.makeText(this, R.string.choose_items_first, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingNearbyAction = PendingNearbyAction.DISCOVER;
        runPendingNearbyAction();
    }

    private void runPendingNearbyAction() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
                || !wifiDirectController.isAvailable()) {
            showNearbyError(R.string.wifi_direct_not_supported);
            pendingNearbyAction = PendingNearbyAction.NONE;
            return;
        }

        if (!NearbyPermissionManager.hasRequiredPermissions(this)) {
            NearbyPermissionManager.requestMissingPermissions(this);
            return;
        }

        if (!NearbyPermissionManager.isLegacyLocationEnabled(this)) {
            showNearbyError(R.string.location_services_required);
            openLocationSettings();
            pendingNearbyAction = PendingNearbyAction.NONE;
            return;
        }

        PendingNearbyAction action = pendingNearbyAction;
        pendingNearbyAction = PendingNearbyAction.NONE;
        if (action == PendingNearbyAction.DISCOVER) {
            wifiDirectController.discoverPeers();
        } else if (action == PendingNearbyAction.RECEIVE) {
            setReceiveState(R.string.receive_preparing, true);
            wifiDirectController.startReceiver();
        }
    }

    private void openLocationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NearbyPermissionManager.REQUEST_CODE) {
            return;
        }

        if (NearbyPermissionManager.hasRequiredPermissions(this)) {
            runPendingNearbyAction();
        } else {
            pendingNearbyAction = PendingNearbyAction.NONE;
            showNearbyError(R.string.nearby_permission_required);
        }
    }

    private void bindBackButton() {
        ImageButton button = findViewById(R.id.button_back);
        button.setOnClickListener(v -> navigateBack());
    }

    private void navigateBack() {
        if (currentScreen == Screen.APPS) {
            showSend();
            return;
        }
        if (currentScreen == Screen.SEND || currentScreen == Screen.RECEIVE) {
            wifiDirectController.stopAll();
        }
        showHome();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILES || resultCode != RESULT_OK || data == null) {
            return;
        }

        selectedFiles.clear();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                addSelectedFile(clipData.getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            addSelectedFile(data.getData());
        }
        updateSelectedFilesText();
    }

    private void addSelectedFile(Uri uri) {
        if (uri == null || selectedFiles.contains(uri)) {
            return;
        }
        selectedFiles.add(uri);
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some document providers grant access only for the current session.
        }
    }

    private void updateSelectedFilesText() {
        if (selectedFilesView == null) {
            return;
        }
        int count = selectedFiles.size();
        if (count == 0) {
            selectedFilesView.setText(R.string.selected_files_none);
        } else if (count == 1) {
            selectedFilesView.setText(getString(R.string.selected_files_count, count));
        } else {
            selectedFilesView.setText(getString(R.string.selected_files_count_many, count));
        }
    }

    private void updateSelectedAppsText() {
        if (selectedAppsView == null) {
            return;
        }
        int count = selectedApps.size();
        if (count == 0) {
            selectedAppsView.setText(R.string.apps_selected_none);
        } else if (count == 1) {
            selectedAppsView.setText(getString(R.string.apps_selected_count, count));
        } else {
            selectedAppsView.setText(getString(R.string.apps_selected_count_many, count));
        }
    }

    private void updateAppsSelectionText(TextView view) {
        int count = selectedApps.size();
        if (count == 0) {
            view.setText(R.string.apps_selected_none);
        } else if (count == 1) {
            view.setText(getString(R.string.apps_selected_count, count));
        } else {
            view.setText(getString(R.string.apps_selected_count_many, count));
        }
    }

    private void setSendState(int messageResId, boolean loading) {
        if (currentScreen != Screen.SEND || sendStatusView == null) {
            return;
        }
        sendStatusView.setText(messageResId);
        sendProgressView.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void setReceiveState(int messageResId, boolean loading) {
        if (currentScreen != Screen.RECEIVE || receiveStatusView == null) {
            return;
        }
        receiveStatusView.setText(messageResId);
        receiveProgressView.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showNearbyError(int messageResId) {
        setSendState(messageResId, false);
        setReceiveState(messageResId, false);
        Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
    }

    private void clearScreenReferences() {
        selectedFilesView = null;
        selectedAppsView = null;
        appsAdapter = null;
        peersAdapter = null;
        sendStatusView = null;
        sendProgressView = null;
        peersListView = null;
        receiveStatusView = null;
        receivePeerView = null;
        receiveProgressView = null;
    }

    @Override
    public void onWifiDirectStateChanged(boolean enabled) {
        if (!enabled) {
            showNearbyError(R.string.enable_wifi);
        }
    }

    @Override
    public void onDiscoveryStarted() {
        setSendState(R.string.searching_nearby_devices, true);
        if (peersListView != null) {
            peersListView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPeersUpdated(List<PeerDevice> peers) {
        if (currentScreen != Screen.SEND || peersAdapter == null) {
            return;
        }
        peersAdapter.replace(peers);
        if (peers.isEmpty()) {
            setSendState(R.string.no_nearby_devices, false);
            peersListView.setVisibility(View.GONE);
        } else {
            sendStatusView.setText(getString(R.string.nearby_devices_count, peers.size()));
            sendProgressView.setVisibility(View.GONE);
            peersListView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onReceiverReady() {
        setReceiveState(R.string.receiver_ready, true);
        if (receivePeerView != null) {
            receivePeerView.setText(R.string.receiver_waiting_for_peer);
        }
    }

    @Override
    public void onConnecting(PeerDevice peer) {
        if (currentScreen == Screen.SEND) {
            sendStatusView.setText(getString(R.string.connecting_to_device, peer.name));
            sendProgressView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLinkEstablished(boolean groupOwner) {
        setSendState(R.string.establishing_secure_channel, true);
        setReceiveState(R.string.establishing_secure_channel, true);
    }

    @Override
    public void onHandshakeCompleted(String peerName) {
        if (currentScreen == Screen.SEND && sendStatusView != null) {
            sendStatusView.setText(getString(R.string.connected_to_device, peerName));
            sendProgressView.setVisibility(View.GONE);
        }
        if (currentScreen == Screen.RECEIVE && receiveStatusView != null) {
            receiveStatusView.setText(R.string.device_connected);
            receiveProgressView.setVisibility(View.GONE);
            receivePeerView.setText(peerName);
        }
    }

    @Override
    public void onDisconnected() {
        setSendState(R.string.connection_stopped, false);
        setReceiveState(R.string.receive_stopped, false);
        if (receivePeerView != null) {
            receivePeerView.setText(R.string.no_connected_device);
        }
    }

    @Override
    public void onWifiDirectError(int messageResId) {
        showNearbyError(messageResId);
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == Screen.HOME) {
            super.onBackPressed();
        } else {
            navigateBack();
        }
    }

    @Override
    protected void onDestroy() {
        wifiDirectController.release();
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
