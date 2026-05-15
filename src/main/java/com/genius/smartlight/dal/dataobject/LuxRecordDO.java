package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("lux_record")
public class LuxRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chip_id")
    private String chipId;

    @TableField("device_id")
    private Long deviceId;

    @TableField("store_id")
    private Long storeId;

    @TableField("lux_value")
    private BigDecimal luxValue;

    @TableField("collect_time")
    private LocalDateTime collectTime;

    @TableField("create_time")
    private LocalDateTime createTime;
}