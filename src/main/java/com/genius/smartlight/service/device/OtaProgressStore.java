package com.genius.smartlight.service.device;

import com.genius.smartlight.vo.device.DeviceRespVO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OtaProgressStore {

    private static final long PROGRESS_TTL_MS = Duration.ofHours(24).toMillis();

    private final ConcurrentHashMap<String, ProgressEntry> progressMap = new ConcurrentHashMap<>();

    public void setProgress(String chipId, Integer progress) {
        if (chipId == null || chipId.isBlank() || progress == null) {
            return;
        }
        progressMap.put(chipId, new ProgressEntry(clamp(progress), System.currentTimeMillis()));
    }

    public Integer getProgress(String chipId) {
        if (chipId == null || chipId.isBlank()) {
            return null;
        }
        ProgressEntry entry = progressMap.get(chipId);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry, System.currentTimeMillis())) {
            progressMap.remove(chipId, entry);
            return null;
        }
        return entry.progress;
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

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupExpiredProgress() {
        long now = System.currentTimeMillis();
        progressMap.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(ProgressEntry entry, long now) {
        return now - entry.updatedAtMs > PROGRESS_TTL_MS;
    }

    private record ProgressEntry(int progress, long updatedAtMs) {
    }
}
