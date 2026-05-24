package com.genius.smartlight.opsadmin;

import lombok.Data;

@Data
public class OpsAdminDurationStats {

    private Long storeId;
    private Long durationToday;
    private Long durationTotal;
    private Long avgDurationToday;
    private Integer durationRecordCountToday;
}
