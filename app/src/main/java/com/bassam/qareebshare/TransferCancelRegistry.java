package com.bassam.qareebshare;

final class TransferCancelRegistry {
    interface CancelAction {
        void cancel();
    }

    private static volatile CancelAction action;

    private TransferCancelRegistry() {
    }

    static void set(CancelAction cancelAction) {
        action = cancelAction;
    }

    static void clear(CancelAction cancelAction) {
        if (action == cancelAction) {
            action = null;
        }
    }

    static void cancel() {
        CancelAction current = action;
        if (current != null) {
            current.cancel();
        }
    }
}
