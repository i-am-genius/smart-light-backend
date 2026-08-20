package com.genius.smartlight.service.device;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OtaDownloadSecurityServicePathTest {

    @Test
    void acceptsCaptureControllerFirmwarePath() {
        OtaDownloadSecurityService service = new OtaDownloadSecurityService(mock(Environment.class));

        assertThat(service.buildRelativePath("cam_capture", "stable", 1))
                .isEqualTo("cam_capture/stable/1/firmware.bin");
    }
}
