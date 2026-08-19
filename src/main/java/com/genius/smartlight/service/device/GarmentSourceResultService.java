package com.genius.smartlight.service.device;

public interface GarmentSourceResultService {

    String PHONE = "PHONE";

    static String camera(String camChipId) {
        return "CAMERA:" + camChipId;
    }

    void saveLatestResult(String lampChipId, String sourceKey);

    void pushLatestResult(String lampChipId);
}
