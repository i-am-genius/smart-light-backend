package com.genius.smartlight.service.device;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomaticGarmentDetectionGateTest {

    @Test
    void startsWhenTheFirstObservedStateIsFullyOnline() {
        AutomaticGarmentDetectionGate gate = new AutomaticGarmentDetectionGate();

        assertThat(gate.evaluate(3, 3))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.START_FULL_SCAN);
        assertThat(gate.evaluate(3, 3))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
    }

    @Test
    void resetsWhenAllDevicesGoOfflineAndStartsOnlyOnceWhenAllReturn() {
        AutomaticGarmentDetectionGate gate = new AutomaticGarmentDetectionGate();

        assertThat(gate.evaluate(3, 0))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.RESET_NOT_DETECTED);
        assertThat(gate.evaluate(3, 1))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
        assertThat(gate.evaluate(3, 2))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
        assertThat(gate.evaluate(3, 3))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.START_FULL_SCAN);
        assertThat(gate.evaluate(3, 2))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
        assertThat(gate.evaluate(3, 3))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
    }

    @Test
    void aNewCompleteOfflineCycleRearamsAutomaticCapture() {
        AutomaticGarmentDetectionGate gate = new AutomaticGarmentDetectionGate();
        gate.evaluate(2, 0);
        gate.evaluate(2, 2);

        assertThat(gate.evaluate(2, 0))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.RESET_NOT_DETECTED);
        assertThat(gate.evaluate(2, 2))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.START_FULL_SCAN);
    }

    @Test
    void ignoresEmptyDeviceSets() {
        AutomaticGarmentDetectionGate gate = new AutomaticGarmentDetectionGate();

        assertThat(gate.evaluate(0, 0))
                .isEqualTo(AutomaticGarmentDetectionGate.Action.NONE);
    }
}
