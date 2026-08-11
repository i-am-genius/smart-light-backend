package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "服装照射标定样本；图像坐标由后端读取当前最新识别结果")
public class DeviceGarmentAimCalibrationSampleReqVO {

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double pan;

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double tilt;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("1200")
    private Double slider;
}
