package com.genius.smartlight.vo.device;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceGarmentAimCalibrationMigrationReqVO {

    @NotBlank
    private String sourceKey;
}
