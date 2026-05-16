package com.genius.smartlight.opsadmin;

import lombok.Data;
import java.util.List;

@Data
public class OpsAdminTimeSeriesExportReq {
    private List<String> storeIds;
    private String startTime;
    private String endTime;
    private List<String> dataTypes;
    private String granularity;
    private List<String> fields;
}
