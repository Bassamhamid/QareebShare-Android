package com.bassam.qareebshare;

import java.util.Locale;

final class FormatUtils {
    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private FormatUtils() {
    }

    static String bytes(long value) {
        if (value < 0L) {
            return "—";
        }
        if (value >= GB) {
            return String.format(Locale.US, "%.2f GB", value / (double) GB);
        }
        if (value >= MB) {
            return String.format(Locale.US, "%.1f MB", value / (double) MB);
        }
        if (value >= KB) {
            return String.format(Locale.US, "%.1f KB", value / (double) KB);
        }
        return value + " B";
    }
}
