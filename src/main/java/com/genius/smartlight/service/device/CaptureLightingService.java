package com.genius.smartlight.service.device;

public interface CaptureLightingService {

    void startStandard(String lampChipId);

    void stop(String lampChipId);

    long getSettleMs();
}
