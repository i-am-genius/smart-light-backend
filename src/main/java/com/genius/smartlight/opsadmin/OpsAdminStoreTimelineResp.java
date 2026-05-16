package com.genius.smartlight.opsadmin;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class OpsAdminStoreTimelineResp {
    private String storeId;
    private String storeName;
    private String startTime;
    private String endTime;
    private String granularity;
    private List<Map<String, Object>> luxSeries;
    private List<Map<String, Object>> durationSeries;
    private List<Map<String, Object>> brightnessSeries;
    private List<Map<String, Object>> tempSeries;
    private List<Map<String, Object>> weatherSeries;
    private Map<String, Object> weatherMeta;
}
