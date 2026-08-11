package com.genius.smartlight.service.device.impl;

import java.util.List;
import java.util.OptionalInt;

/**
 * Resolves the internal camera capture slot from the selected Lamp.
 * The slot remains a device-protocol detail and is not exposed in garment calibration UI.
 */
public final class CameraCapturePresetResolver {

    private CameraCapturePresetResolver() {
    }

    public static OptionalInt resolve(
            Integer requestedTargetIndex,
            String targetChipId,
            List<TargetBinding> bindings) {
        if (requestedTargetIndex != null) {
            return OptionalInt.of(clamp(requestedTargetIndex, 1, 3));
        }
        if (targetChipId == null || targetChipId.isBlank() || bindings == null) {
            return OptionalInt.empty();
        }
        String normalizedTarget = targetChipId.trim();
        return bindings.stream()
                .filter(binding -> binding != null
                        && binding.targetIndex() != null
                        && binding.targetIndex() >= 1
                        && binding.targetIndex() <= 3
                        && binding.targetChipId() != null
                        && normalizedTarget.equalsIgnoreCase(binding.targetChipId().trim()))
                .mapToInt(TargetBinding::targetIndex)
                .findFirst();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record TargetBinding(Integer targetIndex, String targetChipId) {
    }
}
