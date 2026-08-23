package com.genius.smartlight.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.impl.GarmentAimCalibrationServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the calibration-side reader compatible with the evolving
 * garment_source_result_json envelope.
 *
 * <p>The source-result writer currently persists metadata such as
 * latestSourceKey in addition to the sources map. The calibration service
 * only needs the source snapshots themselves, so extra envelope metadata must
 * never make the whole document unreadable.</p>
 */
@Configuration
@RequiredArgsConstructor
public class GarmentSourceResultJacksonCompatibilityConfig {

    private final ObjectMapper objectMapper;

    @PostConstruct
    void registerGarmentSourceResultMixin() {
        objectMapper.addMixIn(
                GarmentAimCalibrationServiceImpl.SourceResultDocument.class,
                IgnoreUnknownSourceResultFields.class
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class IgnoreUnknownSourceResultFields {
    }
}
