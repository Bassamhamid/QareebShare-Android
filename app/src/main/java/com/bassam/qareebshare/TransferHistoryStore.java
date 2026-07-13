package com.bassam.qareebshare;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TransferHistoryStore extends SQLiteOpenHelper {
    static final int DIRECTION_SEND = 1;
    static final int DIRECTION_RECEIVE = 2;
    private static final String DATABASE_NAME = "qareeb_history.db";
    private static final int DATABASE_VERSION = 1;
    private static final int MAX_TRANSFERS = 200;

    static final class Entry {
        final String transferId;
        final int direction;
        final String peerName;
        final int itemCount;
        final long totalBytes;
        final long createdAt;
        final String location;
        final int appCount;

        Entry(
                String transferId,
                int direction,
                String peerName,
                int itemCount,
                long totalBytes,
                long createdAt,
                String location,
                int appCount
        ) {
            this.transferId = transferId;
            this.direction = direction;
            this.peerName = peerName;
            this.itemCount = itemCount;
            this.totalBytes = totalBytes;
            this.createdAt = createdAt;
            this.location = location;
            this.appCount = appCount;
        }
    }

    static final class StoredItem {
        final String displayName;
        final long size;
        final int kind;
        final String groupId;
        final String packageName;
        final String partName;
        final String uri;
        final String filePath;

        StoredItem(
                String displayName,
                long size,
                int kind,
                String groupId,
                String packageName,
                String partName,
                String uri,
                String filePath
        ) {
            this.displayName = displayName;
            this.size = size;
            this.kind = kind;
            this.groupId = groupId;
            this.packageName = packageName;
            this.partName = partName;
            this.uri = uri;
            this.filePath = filePath;
        }
    }

    static final class AppPackage {
        final String packageName;
        final String label;
        final List<StoredItem> parts;

        AppPackage(String packageName, String label, List<StoredItem> parts) {
            this.packageName = packageName;
            this.label = label;
            this.parts = parts;
        }
    }

    TransferHistoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transfers ("
                + "transfer_id TEXT PRIMARY KEY,"
                + "direction INTEGER NOT NULL,"
                + "peer_name TEXT NOT NULL,"
                + "item_count INTEGER NOT NULL,"
                + "total_bytes INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "location TEXT NOT NULL,"
                + "app_count INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE TABLE items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "transfer_id TEXT NOT NULL,"
                + "display_name TEXT NOT NULL,"
                + "size INTEGER NOT NULL,"
                + "kind INTEGER NOT NULL,"
                + "group_id TEXT NOT NULL,"
                + "package_name TEXT NOT NULL,"
                + "part_name TEXT NOT NULL,"
                + "uri TEXT NOT NULL,"
                + "file_path TEXT NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX idx_items_transfer ON items(transfer_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS items");
        db.execSQL("DROP TABLE IF EXISTS transfers");
        onCreate(db);
    }

    synchronized void recordSending(
            String transferId,
            String peerName,
            List<TransferSource> sources,
            long totalBytes
    ) {
        ArrayList<TransferItemInfo> infos = new ArrayList<>();
        for (TransferSource source : sources) {
            infos.add(source.info);
        }
        record(transferId, DIRECTION_SEND, peerName, infos, null, totalBytes, "");
    }

    synchronized void recordReceiving(
            String transferId,
            String peerName,
            List<TransferItemInfo> items,
            List<ReceivedFileStore.StoredLocation> locations,
            long totalBytes,
            String displayLocation
    ) {
        record(
                transferId,
                DIRECTION_RECEIVE,
                peerName,
                items,
                locations,
                totalBytes,
                displayLocation
        );
    }

    private void record(
            String transferId,
            int direction,
            String peerName,
            List<TransferItemInfo> items,
            List<ReceivedFileStore.StoredLocation> locations,
            long totalBytes,
            String displayLocation
    ) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("items", "transfer_id=?", new String[]{transferId});
            db.delete("transfers", "transfer_id=?", new String[]{transferId});

            int appCount = distinctAppCount(items);
            ContentValues transfer = new ContentValues();
            transfer.put("transfer_id", safe(transferId));
            transfer.put("direction", direction);
            transfer.put("peer_name", safe(peerName));
            transfer.put("item_count", items.size());
            transfer.put("total_bytes", totalBytes);
            transfer.put("created_at", System.currentTimeMillis());
            transfer.put("location", safe(displayLocation));
            transfer.put("app_count", appCount);
            db.insertOrThrow("transfers", null, transfer);

            for (int index = 0; index < items.size(); index++) {
                TransferItemInfo item = items.get(index);
                ReceivedFileStore.StoredLocation location = locations != null && index < locations.size()
                        ? locations.get(index) : null;
                ContentValues value = new ContentValues();
                value.put("transfer_id", safe(transferId));
                value.put("display_name", safe(item.displayName));
                value.put("size", item.size);
                value.put("kind", item.kind);
                value.put("group_id", safe(item.groupId));
                value.put("package_name", safe(item.packageName));
                value.put("part_name", safe(item.partName));
                value.put("uri", location == null ? "" : safe(location.uri));
                value.put("file_path", location == null ? "" : safe(location.filePath));
                db.insertOrThrow("items", null, value);
            }
            trimOld(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized List<Entry> listEntries() {
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "transfers",
                new String[]{
                        "transfer_id", "direction", "peer_name", "item_count",
                        "total_bytes", "created_at", "location", "app_count"
                },
                null,
                null,
                null,
                null,
                "created_at DESC",
                Integer.toString(MAX_TRANSFERS)
        )) {
            while (cursor.moveToNext()) {
                result.add(new Entry(
                        cursor.getString(0),
                        cursor.getInt(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getLong(4),
                        cursor.getLong(5),
                        cursor.getString(6),
                        cursor.getInt(7)
                ));
            }
        }
        return result;
    }

    synchronized List<AppPackage> listAppPackages(String transferId) {
        LinkedHashMap<String, ArrayList<StoredItem>> groups = new LinkedHashMap<>();
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        try (Cursor cursor = getReadableDatabase().query(
                "items",
                new String[]{
                        "display_name", "size", "kind", "group_id", "package_name",
                        "part_name", "uri", "file_path"
                },
                "transfer_id=? AND kind=?",
                new String[]{transferId, Integer.toString(TransferItemInfo.KIND_APP)},
                null,
                null,
                "id ASC"
        )) {
            while (cursor.moveToNext()) {
                StoredItem item = new StoredItem(
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getInt(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getString(7)
                );
                String key = item.packageName.isEmpty() ? item.groupId : item.packageName;
                if (key.isEmpty()) {
                    continue;
                }
                ArrayList<StoredItem> parts = groups.get(key);
                if (parts == null) {
                    parts = new ArrayList<>();
                    groups.put(key, parts);
                }
                parts.add(item);
                if (!labels.containsKey(key)) {
                    labels.put(key, labelFromDisplayName(item.displayName, key));
                }
            }
        }

        ArrayList<AppPackage> result = new ArrayList<>();
        for (Map.Entry<String, ArrayList<StoredItem>> group : groups.entrySet()) {
            result.add(new AppPackage(
                    group.getKey(),
                    labels.get(group.getKey()),
                    new ArrayList<>(group.getValue())
            ));
        }
        return result;
    }

    synchronized void clearHistory() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("items", null, null);
            db.delete("transfers", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static int distinctAppCount(List<TransferItemInfo> items) {
        ArrayList<String> packageNames = new ArrayList<>();
        for (TransferItemInfo item : items) {
            if (item.kind != TransferItemInfo.KIND_APP) {
                continue;
            }
            String key = item.packageName.isEmpty() ? item.groupId : item.packageName;
            if (!key.isEmpty() && !packageNames.contains(key)) {
                packageNames.add(key);
            }
        }
        return packageNames.size();
    }

    private static String labelFromDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isEmpty()) {
            return fallback;
        }
        int dashApk = displayName.indexOf("-base.apk");
        if (dashApk > 0) {
            return displayName.substring(0, dashApk);
        }
        if (displayName.endsWith(".apk")) {
            return displayName.substring(0, displayName.length() - 4);
        }
        return displayName;
    }

    private static void trimOld(SQLiteDatabase db) {
        ArrayList<String> oldIds = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT transfer_id FROM transfers ORDER BY created_at DESC LIMIT -1 OFFSET "
                        + MAX_TRANSFERS,
                null
        )) {
            while (cursor.moveToNext()) {
                oldIds.add(cursor.getString(0));
            }
        }
        for (String id : oldIds) {
            db.delete("items", "transfer_id=?", new String[]{id});
            db.delete("transfers", "transfer_id=?", new String[]{id});
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
