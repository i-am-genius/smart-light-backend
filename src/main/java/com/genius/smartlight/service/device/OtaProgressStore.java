package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceRespVO;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OtaProgressStore {

    private final ConcurrentHashMap<String, Integer> progressMap = new ConcurrentHashMap<>();

    public void setProgress(String chipId, Integer progress) {
        if (chipId == null || chipId.isBlank() || progress == null) {
            return;
        }
        progressMap.put(chipId, clamp(progress));
    }

    public Integer getProgress(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return null;
        }
        return progressMap.get(chipId);
    }

    public void clearProgress(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return;
        }
        progressMap.remove(chipId);
    }

    public Integer resolveProgress(String chipId, String otaStatus) {
        String status = otaStatus == null ? "" : otaStatus.trim().toLowerCase(Locale.ROOT);
        if ("success".equals(status)) {
            return 100;
        }
        if ("idle".equals(status)) {
            return 0;
        }
        Integer progress = getProgress(chipId);
        return progress == null ? 0 : progress;
    }

    public DeviceRespVO applyProgress(DeviceRespVO respVO) {
        if (respVO == null) {
            return null;
        }
        respVO.setOtaProgress(resolveProgress(respVO.getChipId(), respVO.getOtaStatus()));
        return respVO;
    }

    private int clamp(Integer progress) {
        return Math.max(0, Math.min(100, progress));
    }
}
