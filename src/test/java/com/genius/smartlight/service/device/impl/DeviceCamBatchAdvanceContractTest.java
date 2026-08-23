package com.genius.smartlight.service.device.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCamBatchAdvanceContractTest {

    private static final Path SERVICE_SOURCE = Path.of(
            "src", "main", "java", "com", "genius", "smartlight",
            "service", "device", "impl", "DeviceCamServiceImpl.java"
    );

    @Test
    void batchAdvanceRequiresServerConfirmedStoredImage() throws IOException {
        String source = Files.readString(SERVICE_SOURCE);
        String method = methodBody(source, "private void advanceCaptureBatch", "private void failBatchPhysicalTask");

        assertThat(method)
                .contains("\"image_received\".equals(completedTask.getStatus())")
                .contains("completedTask.getImageName()")
                .contains("batch advance blocked: photo not confirmed");
    }

    @Test
    void batchPhysicalFailureStopsBatchInsteadOfStartingNextSliderMove() throws IOException {
        String source = Files.readString(SERVICE_SOURCE);
        String method = methodBody(source, "private void failBatchPhysicalTask", "private void startBatchReturn");

        assertThat(method)
                .contains("finishCaptureBatch(")
                .contains("\"error\"")
                .doesNotContain("advanceCaptureBatch(")
                .doesNotContain("startBatchTarget(");
    }

    @Test
    void successfulUploadStillAdvancesOnlyAfterImageReceivedIsSet() throws IOException {
        String source = Files.readString(SERVICE_SOURCE);
        String method = methodBody(source, "public DeviceCamCaptureTaskRespVO uploadCapturePhoto", "private void startSingleCaptureReturn");

        int imageReceived = method.indexOf("task.setStatus(\"image_received\")");
        int advance = method.indexOf("advanceCaptureBatch(task)");
        assertThat(imageReceived).isGreaterThanOrEqualTo(0);
        assertThat(advance).isGreaterThan(imageReceived);
    }

    private String methodBody(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(start).as("start marker %s", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }
}
