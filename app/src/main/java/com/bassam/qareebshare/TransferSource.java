package com.bassam.qareebshare;

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
}
