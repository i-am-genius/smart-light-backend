package com.genius.smartlight.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GarmentAimCalibrationFitterTest {

    @Test
    void fitsAffinePanTiltOffsetsFromDistributedManualSamples() {
        List<GarmentAimCalibrationFitter.Sample> samples = List.of(
                sample(0.15, 0.20),
                sample(0.80, 0.18),
                sample(0.20, 0.82),
                sample(0.78, 0.78),
                sample(0.50, 0.50),
                sample(0.35, 0.65)
        );

        GarmentAimCalibrationFitter.FitResult result =
                GarmentAimCalibrationFitter.fit(samples);

        assertThat(result.ready()).isTrue();
        GarmentAimCalibrationFitter.Pose prediction = result.model().predict(0.62, 0.37);
        assertThat(prediction.pan()).isCloseTo(panOffset(0.62, 0.37), within(1.0E-8));
        assertThat(prediction.tilt()).isCloseTo(tiltOffset(0.62, 0.37), within(1.0E-8));
        assertThat(result.model().pan().rmse()).isLessThan(1.0E-8);
    }

    @Test
    void waitsForEnoughSpatialCoverage() {
        GarmentAimCalibrationFitter.FitResult tooFew = GarmentAimCalibrationFitter.fit(List.of(
                sample(0.2, 0.2), sample(0.8, 0.2), sample(0.2, 0.8)
        ));
        assertThat(tooFew.ready()).isFalse();
        assertThat(tooFew.reason()).isEqualTo("insufficient_samples");

        GarmentAimCalibrationFitter.FitResult narrow = GarmentAimCalibrationFitter.fit(List.of(
                sample(0.48, 0.20), sample(0.50, 0.40),
                sample(0.52, 0.60), sample(0.49, 0.80)
        ));
        assertThat(narrow.ready()).isFalse();
        assertThat(narrow.reason()).isEqualTo("insufficient_coverage");
    }

    private GarmentAimCalibrationFitter.Sample sample(double x, double y) {
        return new GarmentAimCalibrationFitter.Sample(x, y, panOffset(x, y), tiltOffset(x, y));
    }

    private double panOffset(double x, double y) {
        return -12D + 24D * x + 6D * y;
    }

    private double tiltOffset(double x, double y) {
        return 8D - 5D * x - 12D * y;
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
