package com.bassam.qareebshare;

final class TransferItemInfo {
    static final int KIND_FILE = 0;
    static final int KIND_APP = 1;

    final String id;
    final String displayName;
    final String mimeType;
    final long size;
    final int kind;
    final String groupId;
    final String packageName;
    final String partName;

    TransferItemInfo(
            String id,
            String displayName,
            String mimeType,
            long size,
            int kind,
            String groupId,
            String packageName,
            String partName
    ) {
        this.id = safe(id);
        this.displayName = safe(displayName);
        this.mimeType = safe(mimeType);
        this.size = size;
        this.kind = kind;
        this.groupId = safe(groupId);
        this.packageName = safe(packageName);
        this.partName = safe(partName);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
