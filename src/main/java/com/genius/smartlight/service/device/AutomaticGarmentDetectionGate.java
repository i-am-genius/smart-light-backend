package com.genius.smartlight.service.device;

/**
 * Per-store edge detector for one automatic full-region scan per complete
 * offline -> online lifecycle.
 */
public final class AutomaticGarmentDetectionGate {

    public enum Action {
        NONE,
        RESET_NOT_DETECTED,
        START_FULL_SCAN
    }

    private boolean armed = true;
    private boolean allOfflineObserved;

    public synchronized Action evaluate(int requiredDeviceCount, int onlineDeviceCount) {
        if (requiredDeviceCount <= 0) {
            return Action.NONE;
        }
        int boundedOnlineCount = Math.max(0, Math.min(requiredDeviceCount, onlineDeviceCount));
        if (boundedOnlineCount == 0) {
            boolean firstOfflineObservation = !allOfflineObserved;
            allOfflineObserved = true;
            armed = true;
            return firstOfflineObservation ? Action.RESET_NOT_DETECTED : Action.NONE;
        }

        allOfflineObserved = false;
        if (boundedOnlineCount == requiredDeviceCount && armed) {
            armed = false;
            return Action.START_FULL_SCAN;
        }
        return Action.NONE;
    }
}
