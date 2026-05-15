package com.genius.smartlight.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MainColorResult {

    private String mainColorRgb;

    private Integer recommendedBrightness;

    private Integer recommendedTemp;
}