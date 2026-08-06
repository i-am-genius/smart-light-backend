package com.genius.smartlight.vo.ai;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GarmentResultSnapshot {

    private Integer resultVersion;
    private Boolean clothDetected;
    private Boolean segmentationFallback;
    private String outfitType;
    private LocalDateTime recognizedAt;
    private List<GarmentPartRespVO> garments;
}
