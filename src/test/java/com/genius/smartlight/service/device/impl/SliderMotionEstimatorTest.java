package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SliderMotionEstimatorTest {

    @Test
    void estimatesFromCurrentPositionAndAddsThreeHundredMilliseconds() {
        long delayMs = SliderMotionEstimator.estimateDelayMs(
                300D,
                900D,
                1200D,
                12D
        );

        assertThat(delayMs).isEqualTo(6_300L);
    }

    @Test
    void keepsSafetyMarginWhenSliderIsAlreadyAtTarget() {
        assertThat(SliderMotionEstimator.estimateDelayMs(500D, 500D, 1000D, 10D))
                .isEqualTo(300L);
    }

    @Test
    void rejectsMissingOrInvalidCalibration() {
        assertThatThrownBy(() -> SliderMotionEstimator.estimateDelayMs(0D, 500D, 0D, 10D))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("滑轨移动时间");
        assertThatThrownBy(() -> SliderMotionEstimator.estimateDelayMs(0D, 500D, 500D, 0D))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("滑轨移动时间");
    }

    @Test
    void enforcesTheSharedTwoThousandFiveHundredMillimetreLimit() {
        assertThatThrownBy(() -> SliderMotionEstimator.estimateDelayMs(0D, 2501D, 500D, 5D))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("2500");
    }
}
