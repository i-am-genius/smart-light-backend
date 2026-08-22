package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SliderCollisionPlannerTest {
    @Test
    void parksEveryLampWhosePresetLiesOnPathAndAddsSafetyMargin() {
        SliderCollisionPlanner.CollisionPlan plan = SliderCollisionPlanner.plan(
                100D, 900D,
                List.of(roi(1, "L1", 0.7D), roi(2, "L2", 1.2D), roi(3, "L3", 0.5D)),
                Map.of("1", 300D, "2", 700D, "3", 1_200D));
        assertThat(plan.lamps()).extracting(SliderCollisionPlanner.GuardedLamp::chipId)
                .containsExactly("L1", "L2");
        assertThat(plan.parkDelayMs()).isEqualTo(1_500L);
    }

    @Test
    void parksTargetLampWhenSliderAlreadyAtTarget() {
        SliderCollisionPlanner.CollisionPlan plan = SliderCollisionPlanner.plan(
                300D, 300D, List.of(roi(1, "L1", 0.7D)), Map.of("1", 300D));

        assertThat(plan.lamps()).extracting(SliderCollisionPlanner.GuardedLamp::chipId)
                .containsExactly("L1");
    }

    @Test
    void ignoresLegacyCollisionCenterAndClearance() {
        DeviceCamRoiItemVO item = roi(1, "L1", 0.7D);
        item.setCollisionCenterMm(2_000D);
        item.setCollisionClearanceMm(0D);

        SliderCollisionPlanner.CollisionPlan plan = SliderCollisionPlanner.plan(
                100D, 500D, List.of(item), Map.of("1", 300D));

        assertThat(plan.lamps()).extracting(SliderCollisionPlanner.GuardedLamp::chipId)
                .containsExactly("L1");
    }

    @Test
    void rejectsMissingParkCalibrationForLampOnPath() {
        assertThatThrownBy(() -> SliderCollisionPlanner.plan(
                100D, 900D, List.of(roi(1, "L1", 0D)), Map.of("1", 300D)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("回零时间");
    }

    private DeviceCamRoiItemVO roi(int index, String id, double seconds) {
        DeviceCamRoiItemVO item = new DeviceCamRoiItemVO();
        item.setTargetIndex(index);
        item.setTargetChipId(id);
        item.setCollisionParkTimeSeconds(seconds);
        return item;
    }
}
