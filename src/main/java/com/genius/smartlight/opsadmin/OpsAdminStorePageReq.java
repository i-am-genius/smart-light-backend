package com.genius.smartlight.opsadmin;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OpsAdminStorePageReq {
    private int page = 1;
    private int pageSize = 20;
    private String keyword;
    private String province;
    private String city;
    private String storeStyle;
    private BigDecimal minArea;
    private BigDecimal maxArea;
    private String hasDevices;
    private String hasCamlamp;
    private String autoMode;
    private String firmwareChannel;
    private String sortBy;
    private String sortOrder;
}
