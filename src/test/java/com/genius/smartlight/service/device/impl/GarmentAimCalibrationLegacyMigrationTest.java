package com.genius.smartlight.service.device.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GarmentAimCalibrationLegacyMigrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyAbsolutePoseBecomesOffsetAndDropsSliderOnNextWrite() throws Exception {
        GarmentAimCalibrationServiceImpl.StoredSample sample =
                new GarmentAimCalibrationServiceImpl.StoredSample();
        sample.setPan(12D);
        sample.setTilt(26D);
        sample.setSlider(320D);

        GarmentAimCalibrationServiceImpl.CalibrationDocument document =
                new GarmentAimCalibrationServiceImpl.CalibrationDocument();
        document.setVersion(1);
        document.setSamples(List.of(sample));

        GarmentAimCalibrationServiceImpl.migrateLegacySamples(document);
        String json = objectMapper.writeValueAsString(document);

        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(sample.getPanOffset()).isEqualTo(12D);
        assertThat(sample.getTiltOffset()).isEqualTo(6D);
        assertThat(json).contains(
                "\"panOffset\":12.0",
                "\"tiltOffset\":6.0"
        );
        assertThat(json).doesNotContain(
                "\"pan\":",
                "\"tilt\":",
                "\"slider\":"
        );
    }
}
