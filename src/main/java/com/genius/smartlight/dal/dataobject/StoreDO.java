package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("store")
@Data
public class StoreDO {
    private Long id;
    private Long userId;
    private String storeName;
    private String storeStyle;
    private BigDecimal area;
    private String province;
    private String city;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
