package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("duration_record")
public class DurationRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chip_id")
    private String chipId;

    @TableField("device_id")
    private Long deviceId;

    @TableField("store_id")
    private Long storeId;

    @TableField("stat_date")
    private LocalDate statDate;

    @TableField("duration_value")
    private Long durationValue;

    @TableField("collect_time")
    private LocalDateTime collectTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}