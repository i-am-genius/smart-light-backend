package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SliderCollisionPlannerTest {
    @Test
    void parksIntersectingLampsAndAddsSafetyMargin() {
        SliderCollisionPlanner.CollisionPlan plan = SliderCollisionPlanner.plan(
                100D, 900D, List.of(zone("L1", 300D, 40D, 0.7D), zone("L2", 700D, 60D, 1.2D)));
        assertThat(plan.lamps()).extracting(SliderCollisionPlanner.GuardedLamp::chipId)
                .containsExactly("L1", "L2");
        assertThat(plan.parkDelayMs()).isEqualTo(1_500L);
    }

    @Test
    void rejectsMissingParkCalibration() {
        assertThatThrownBy(() -> SliderCollisionPlanner.plan(
                100D, 900D, List.of(zone("L1", 300D, 40D, 0D))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("避让时间");
    }

    private DeviceCamRoiItemVO zone(String id, double center, double clearance, double seconds) {
        DeviceCamRoiItemVO item = new DeviceCamRoiItemVO();
        item.setTargetChipId(id);
        item.setCollisionCenterMm(center);
        item.setCollisionClearanceMm(clearance);
        item.setCollisionParkTimeSeconds(seconds);
        return item;
    }
}
