package com.genius.smartlight.vo.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeviceGarmentAimCalibrationCopyReqVO {

    @NotBlank
    private String sourceKey;

    @NotEmpty
    private List<String> targetLampChipIds = new ArrayList<>();

    private Boolean overwrite = false;
}
