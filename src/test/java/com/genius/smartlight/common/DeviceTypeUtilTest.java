package com.genius.smartlight.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTypeUtilTest {

    @Test
    void acceptsCaptureControllerAsDedicatedNonLampDeviceType() {
        assertThat(DeviceTypeUtil.normalizeAndValidate(" CAM_CAPTURE ")).isEqualTo("cam_capture");
        assertThat(DeviceTypeUtil.isCaptureController("cam_capture")).isTrue();
        assertThat(DeviceTypeUtil.isCam("cam_capture")).isFalse();
        assertThat(DeviceTypeUtil.isLampLike("cam_capture")).isFalse();
    }
}
