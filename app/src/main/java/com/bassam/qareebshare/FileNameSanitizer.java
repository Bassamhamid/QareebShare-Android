package com.bassam.qareebshare;

final class FileNameSanitizer {
    private FileNameSanitizer() {
    }

    static String sanitize(String name, String fallback) {
        String value = name == null ? "" : name.trim();
        value = value.replace('\\', '_').replace('/', '_');
        value = value.replace("..", "_");
        value = value.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_");
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        value = value.trim();
        if (value.isEmpty()) {
            value = fallback == null || fallback.trim().isEmpty() ? "ملف" : fallback.trim();
        }
        if (value.length() > 180) {
            int dot = value.lastIndexOf('.');
            String extension = dot > 0 && value.length() - dot <= 16 ? value.substring(dot) : "";
            int maxBase = Math.max(1, 180 - extension.length());
            value = value.substring(0, Math.min(maxBase, value.length())) + extension;
        }
        return value;
    }
}
