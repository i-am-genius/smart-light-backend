package com.genius.smartlight.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.impl.GarmentAimCalibrationServiceImpl;
import com.genius.smartlight.service.device.impl.GarmentSourceResultServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GarmentSourceResultJacksonCompatibilityConfigTest {

    @Test
    void calibrationReaderAcceptsLatestSourceMetadataWrittenBySourceService() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        new GarmentSourceResultJacksonCompatibilityConfig(objectMapper)
                .registerGarmentSourceResultMixin();

        GarmentSourceResultServiceImpl.SourceResultDocument written =
                new GarmentSourceResultServiceImpl.SourceResultDocument();
        written.setVersion(1);
        written.setLatestSourceKey("PHONE");
        written.getSources().put("PHONE", "{\"recognizedAt\":\"2026-08-23T08:00:00\"}");

        String json = objectMapper.writeValueAsString(written);
        assertThat(json).contains("\"latestSourceKey\":\"PHONE\"");

        GarmentAimCalibrationServiceImpl.SourceResultDocument read = objectMapper.readValue(
                json,
                GarmentAimCalibrationServiceImpl.SourceResultDocument.class
        );

        assertThat(read.getSources()).containsEntry(
                "PHONE",
                "{\"recognizedAt\":\"2026-08-23T08:00:00\"}"
        );
    }

    @Test
    void calibrationReaderIgnoresFutureEnvelopeMetadataWithoutDroppingCameraSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        new GarmentSourceResultJacksonCompatibilityConfig(objectMapper)
                .registerGarmentSourceResultMixin();

        String json = "{"
                + "\"version\":1,"
                + "\"latestSourceKey\":\"CAMERA:CAM-1\","
                + "\"futureMetadata\":{\"protocolVersion\":2},"
                + "\"sources\":{\"CAMERA:CAM-1\":\"camera-snapshot\"}"
                + "}";

        GarmentAimCalibrationServiceImpl.SourceResultDocument read = objectMapper.readValue(
                json,
                GarmentAimCalibrationServiceImpl.SourceResultDocument.class
        );

        assertThat(read.getSources()).containsEntry("CAMERA:CAM-1", "camera-snapshot");
    }
}
