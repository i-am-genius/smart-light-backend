package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DeviceLastSeenService {

    private static final long PERSIST_INTERVAL_MS = 60_000L;
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final DeviceMapper deviceMapper;
    private final Map<String, Long> lastPersistedMsMap = new ConcurrentHashMap<>();

    public LocalDateTime persistNow(String chipId, Long seenMs) {
        return persist(chipId, seenMs, true);
    }

    public LocalDateTime persistIfDue(String chipId, Long seenMs) {
        return persist(chipId, seenMs, false);
    }

    private LocalDateTime persist(String chipId, Long seenMs, boolean force) {
        if (chipId == null || chipId.isBlank() || seenMs == null || seenMs <= 0) {
            return null;
        }

        Long lastPersistedMs = lastPersistedMsMap.get(chipId);
        if (!force && lastPersistedMs != null && seenMs - lastPersistedMs < PERSIST_INTERVAL_MS) {
            return null;
        }

        LocalDateTime seenAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(seenMs), ZONE_ID);
        int updated = deviceMapper.update(
                null,
                new LambdaUpdateWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
                        .set(DeviceDO::getLastSeenAt, seenAt)
        );
        if (updated > 0) {
            lastPersistedMsMap.put(chipId, seenMs);
            return seenAt;
        }
        return null;
    }
}
