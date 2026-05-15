package com.genius.smartlight.vo.device;

import lombok.Data;

@Data
public class LightEffectReqVO {

    private String effect;

    private Boolean enabled;

    private Integer baseTemp;

    private Integer range;

    private Double speed;

    private Integer brightness;

    private Integer phaseIndex;

    private Double phaseGap;
}