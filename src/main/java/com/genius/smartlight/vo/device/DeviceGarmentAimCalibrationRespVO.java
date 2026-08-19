package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Lamp 服装坐标现场标定状态")
public class DeviceGarmentAimCalibrationRespVO {

    private String lampChipId;
    private String sourceKey;
    private Integer sampleCount;
    private Integer minimumSampleCount;
    private Integer recommendedSampleCount;
    private Boolean modelReady;
    private String statusCode;
    private String statusMessage;
    private Double horizontalCoverage;
    private Double verticalCoverage;
    private Double rmsePan;
    private Double rmseTilt;
    private Boolean legacyMigrationRequired;
    private Integer legacySampleCount;
    private Boolean currentTargetValid;
    private Boolean currentTargetSampled;
    private Double currentCenterX;
    private Double currentCenterY;
    private LocalDateTime currentRecognizedAt;
    private Double suggestedPan;
    private Double suggestedTilt;
    private String suggestionSource;
    private LocalDateTime updatedAt;
    private List<Sample> samples = new ArrayList<>();

    @Data
    public static class Sample {
        private String id;
        private Double centerX;
        private Double centerY;
        private Double pan;
        private Double tilt;
        private LocalDateTime recognizedAt;
        private LocalDateTime createdAt;
    }
}
