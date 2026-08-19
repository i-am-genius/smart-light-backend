package com.genius.smartlight.service.device;

public interface CaptureLightingService {

    /**
     * Acquire or refresh a capture-lighting lease for one Lamp.
     *
     * @param lampChipId target Lamp
     * @param sessionId owner/session identifier; blank means generate one
     * @return normalized session identifier
     */
    String startStandard(String lampChipId, String sessionId);

    /**
     * Release only the matching capture-lighting lease. The Lamp is switched
     * back to normal lighting only when no other live lease remains.
     */
    void stop(String lampChipId, String sessionId);

    long getSettleMs();
}
