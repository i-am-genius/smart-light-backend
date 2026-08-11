package com.genius.smartlight.service.device.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CameraCapturePresetResolverTest {

    @Test
    void resolvesInternalCaptureSlotFromSelectedLamp() {
        int resolved = CameraCapturePresetResolver.resolve(
                null,
                "lamp-002",
                List.of(
                        new CameraCapturePresetResolver.TargetBinding(1, "LAMP-001"),
                        new CameraCapturePresetResolver.TargetBinding(2, "LAMP-002")
                )
        ).orElseThrow();

        assertThat(resolved).isEqualTo(2);
    }

    @Test
    void keepsExplicitSlotForLegacyManualCapture() {
        int resolved = CameraCapturePresetResolver.resolve(
                3,
                "LAMP-002",
                List.of(new CameraCapturePresetResolver.TargetBinding(2, "LAMP-002"))
        ).orElseThrow();

        assertThat(resolved).isEqualTo(3);
    }

    @Test
    void returnsEmptyWhenSelectedLampHasNoCapturePreset() {
        assertThat(CameraCapturePresetResolver.resolve(
                null,
                "LAMP-003",
                List.of(new CameraCapturePresetResolver.TargetBinding(1, "LAMP-001"))
        )).isEmpty();
    }
}
