package com.bassam.qareebshare;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

final class TransferSource {
    interface Opener {
        InputStream open() throws IOException;
    }

    final TransferItemInfo info;
    final Opener opener;

    TransferSource(TransferItemInfo info, Opener opener) {
        this.info = info;
        this.opener = opener;
    }

    InputStream openAt(long offset) throws IOException {
        InputStream input = opener.open();
        try {
            skipFully(input, Math.max(0L, offset));
            return input;
        } catch (IOException error) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
            throw error;
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        byte[] fallback = new byte[32 * 1024];
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            int read = input.read(fallback, 0, (int) Math.min(fallback.length, remaining));
            if (read < 0) {
                throw new EOFException("Unable to resume selected source");
            }
            remaining -= read;
        }
    }
}
