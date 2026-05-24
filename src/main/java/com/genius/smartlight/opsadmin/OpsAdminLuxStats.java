package com.genius.smartlight.opsadmin;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OpsAdminLuxStats {

    private Long storeId;
    private BigDecimal latestLux;
    private LocalDateTime latestLuxTime;
    private BigDecimal avgLuxToday;
    private BigDecimal maxLuxToday;
    private BigDecimal minLuxToday;
    private Integer luxRecordCountToday;
}
