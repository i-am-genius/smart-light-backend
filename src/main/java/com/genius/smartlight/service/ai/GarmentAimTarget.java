package com.genius.smartlight.service.ai;

import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.ai.GarmentResultSnapshot;
import com.genius.smartlight.vo.device.DeviceRespVO;

import java.util.List;
import java.util.Optional;

/**
 * Compact, image-normalized target sent to a lamp after garment recognition.
 */
public record GarmentAimTarget(
        int x,
        int y,
        int w,
        int h,
        int imageWidth,
        int imageHeight,
        double centerX,
        double centerY) {

    public static Optional<GarmentAimTarget> from(DeviceRespVO state) {
        if (state == null) {
            return Optional.empty();
        }

        return from(
                state.getClothDetected(),
                state.getImageWidth(),
                state.getImageHeight(),
                state.getGarments()
        );
    }

    public static Optional<GarmentAimTarget> from(GarmentResultSnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }

        return from(
                snapshot.getClothDetected(),
                snapshot.getImageWidth(),
                snapshot.getImageHeight(),
                snapshot.getGarments()
        );
    }

    private static Optional<GarmentAimTarget> from(
            Boolean clothDetected,
            Integer imageWidth,
            Integer imageHeight,
            List<GarmentPartRespVO> garments) {
        if (!Boolean.TRUE.equals(clothDetected)) {
            return Optional.empty();
        }

        if (imageWidth == null || imageWidth <= 0
                || imageHeight == null || imageHeight <= 0
                || garments == null || garments.isEmpty()) {
            return Optional.empty();
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (GarmentPartRespVO garment : garments) {
            if (!isValidBox(garment, imageWidth, imageHeight)) {
                return Optional.empty();
            }
            minX = Math.min(minX, garment.getX());
            minY = Math.min(minY, garment.getY());
            maxX = Math.max(maxX, garment.getX() + garment.getW());
            maxY = Math.max(maxY, garment.getY() + garment.getH());
        }

        int width = maxX - minX;
        int height = maxY - minY;
        double centerX = (minX + width / 2D) / imageWidth;
        double centerY = (minY + height / 2D) / imageHeight;
        return Optional.of(new GarmentAimTarget(
                minX,
                minY,
                width,
                height,
                imageWidth,
                imageHeight,
                centerX,
                centerY
        ));
    }

    private static boolean isValidBox(
            GarmentPartRespVO garment,
            int imageWidth,
            int imageHeight) {
        if (garment == null
                || garment.getX() == null || garment.getX() < 0
                || garment.getY() == null || garment.getY() < 0
                || garment.getW() == null || garment.getW() <= 0
                || garment.getH() == null || garment.getH() <= 0) {
            return false;
        }
        long right = (long) garment.getX() + garment.getW();
        long bottom = (long) garment.getY() + garment.getH();
        return right <= imageWidth && bottom <= imageHeight;
    }
}
