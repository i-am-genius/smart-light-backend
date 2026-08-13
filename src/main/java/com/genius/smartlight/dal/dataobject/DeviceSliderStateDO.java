package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device_slider_state")
public class DeviceSliderStateDO {

    @TableId(value = "chip_id", type = IdType.INPUT)
    private String chipId;
    private Long storeId;
    private Double currentPositionMm;
    private Double targetPositionMm;
    private String speedMode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime motionStartedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime motionEndAt;
    private LocalDateTime updateTime;
}
