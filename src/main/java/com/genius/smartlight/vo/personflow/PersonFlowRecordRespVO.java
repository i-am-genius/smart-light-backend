package com.genius.smartlight.vo.personflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "人流检测记录响应")
@Data
public class PersonFlowRecordRespVO {

    @Schema(description = "记录ID", example = "1")
    private Long id;

    @Schema(description = "设备芯片ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "检测来源：UPLOAD/CAMERA", example = "UPLOAD")
    private String source;

    @Schema(description = "检测人数", example = "3")
    private Integer personCount;

    @Schema(description = "平均置信度", example = "0.88")
    private Double confidence;

    @Schema(description = "处理时间，单位毫秒", example = "123.45")
    private Double processingTime;

    @Schema(description = "检测时间", example = "2026-05-24T23:59:00")
    private LocalDateTime detectTime;

    @Schema(description = "原图文件名", example = "store_cam.jpg")
    private String imageName;

    @Schema(description = "记录创建时间", example = "2026-05-24T23:59:01")
    private LocalDateTime createTime;
}
