package com.genius.smartlight.service.ai;

import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GarmentAimTargetTest {

    @Test
    void calculatesNormalizedUnionCenterFromDetectedGarments() {
        DeviceRespVO state = state(true, 640, 480,
                garment(100, 80, 200, 120),
                garment(120, 200, 240, 180));

        assertThat(GarmentAimTarget.from(state)).hasValueSatisfying(target -> {
            assertThat(target.x()).isEqualTo(100);
            assertThat(target.y()).isEqualTo(80);
            assertThat(target.w()).isEqualTo(260);
            assertThat(target.h()).isEqualTo(300);
            assertThat(target.centerX()).isEqualTo(230D / 640D);
            assertThat(target.centerY()).isEqualTo(230D / 480D);
        });
    }

    @Test
    void rejectsFallbackMissingDimensionsAndOutOfBoundsBoxes() {
        assertThat(GarmentAimTarget.from(
                state(false, 640, 480, garment(100, 80, 200, 120))
        )).isEmpty();
        assertThat(GarmentAimTarget.from(
                state(true, null, 480, garment(100, 80, 200, 120))
        )).isEmpty();
        assertThat(GarmentAimTarget.from(
                state(true, 640, 480, garment(600, 80, 100, 120))
        )).isEmpty();
    }

    private static DeviceRespVO state(
            boolean detected,
            Integer imageWidth,
            Integer imageHeight,
            GarmentPartRespVO... garments) {
        DeviceRespVO state = new DeviceRespVO();
        state.setClothDetected(detected);
        state.setImageWidth(imageWidth);
        state.setImageHeight(imageHeight);
        state.setGarments(List.of(garments));
        return state;
    }

    private static GarmentPartRespVO garment(int x, int y, int w, int h) {
        GarmentPartRespVO garment = new GarmentPartRespVO();
        garment.setX(x);
        garment.setY(y);
        garment.setW(w);
        garment.setH(h);
        return garment;
    }
}
