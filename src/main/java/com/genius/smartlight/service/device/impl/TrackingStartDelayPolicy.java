package com.genius.smartlight.service.device.impl;

final class TrackingStartDelayPolicy {

    static final long MINIMUM_DELAY_MS = 2_000L;

    private TrackingStartDelayPolicy() {
    }

    static long delayMs(long motionDelayMs) {
        return Math.max(MINIMUM_DELAY_MS, Math.max(0L, motionDelayMs));
    }
}

