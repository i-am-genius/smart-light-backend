package com.genius.smartlight.service.device.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingStartDelayPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "300, 2000",
            "800, 2000",
            "2000, 2000",
            "2100, 2100",
            "2300, 2300",
            "3800, 3800"
    })
    void appliesTwoSecondMinimumToMotionDelayThatAlreadyIncludesSafetyMargin(
            long motionDelayMs,
            long expectedTrackingDelayMs) {
        assertThat(TrackingStartDelayPolicy.delayMs(motionDelayMs))
                .isEqualTo(expectedTrackingDelayMs);
    }
}

