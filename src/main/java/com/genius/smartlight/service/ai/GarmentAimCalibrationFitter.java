package com.genius.smartlight.service.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits affine garment Pan/Tilt offsets from the configured default pose:
 * offset = intercept + xCoefficient * centerX + yCoefficient * centerY.
 */
public final class GarmentAimCalibrationFitter {

    public static final int MIN_SAMPLE_COUNT = 4;
    public static final int RECOMMENDED_SAMPLE_COUNT = 6;
    public static final double MIN_AXIS_COVERAGE = 0.12D;
    private static final double PIVOT_EPSILON = 1.0E-9D;

    private GarmentAimCalibrationFitter() {
    }

    public static FitResult fit(List<Sample> input) {
        List<Sample> samples = input == null
                ? List.of()
                : input.stream().filter(GarmentAimCalibrationFitter::valid).toList();
        double xCoverage = coverage(samples, true);
        double yCoverage = coverage(samples, false);
        if (samples.size() < MIN_SAMPLE_COUNT) {
            return new FitResult(false, "insufficient_samples", xCoverage, yCoverage, null);
        }
        if (xCoverage < MIN_AXIS_COVERAGE || yCoverage < MIN_AXIS_COVERAGE) {
            return new FitResult(false, "insufficient_coverage", xCoverage, yCoverage, null);
        }

        AxisModel pan = fitAxis(samples, Axis.PAN);
        AxisModel tilt = fitAxis(samples, Axis.TILT);
        if (pan == null || tilt == null) {
            return new FitResult(false, "degenerate_samples", xCoverage, yCoverage, null);
        }
        return new FitResult(true, "ready", xCoverage, yCoverage, new Model(pan, tilt));
    }

    private static boolean valid(Sample sample) {
        return sample != null
                && finite(sample.centerX()) && sample.centerX() >= 0D && sample.centerX() <= 1D
                && finite(sample.centerY()) && sample.centerY() >= 0D && sample.centerY() <= 1D
                && finite(sample.pan()) && finite(sample.tilt());
    }

    private static double coverage(List<Sample> samples, boolean horizontal) {
        if (samples.isEmpty()) {
            return 0D;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Sample sample : samples) {
            double value = horizontal ? sample.centerX() : sample.centerY();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private static AxisModel fitAxis(List<Sample> samples, Axis axis) {
        double[][] normal = new double[3][3];
        double[] rhs = new double[3];
        for (Sample sample : samples) {
            double[] feature = {1D, sample.centerX(), sample.centerY()};
            double output = axis.value(sample);
            for (int row = 0; row < feature.length; row++) {
                rhs[row] += feature[row] * output;
                for (int column = 0; column < feature.length; column++) {
                    normal[row][column] += feature[row] * feature[column];
                }
            }
        }

        double[] coefficient = solve(normal, rhs);
        if (coefficient == null) {
            return null;
        }
        double squaredError = 0D;
        for (Sample sample : samples) {
            double predicted = coefficient[0]
                    + coefficient[1] * sample.centerX()
                    + coefficient[2] * sample.centerY();
            double error = predicted - axis.value(sample);
            squaredError += error * error;
        }
        return new AxisModel(
                coefficient[0],
                coefficient[1],
                coefficient[2],
                Math.sqrt(squaredError / samples.size())
        );
    }

    private static double[] solve(double[][] matrix, double[] values) {
        List<double[]> rows = new ArrayList<>(matrix.length);
        for (int index = 0; index < matrix.length; index++) {
            rows.add(new double[]{
                    matrix[index][0], matrix[index][1], matrix[index][2], values[index]
            });
        }

        for (int pivot = 0; pivot < 3; pivot++) {
            int bestRow = pivot;
            for (int row = pivot + 1; row < 3; row++) {
                if (Math.abs(rows.get(row)[pivot]) > Math.abs(rows.get(bestRow)[pivot])) {
                    bestRow = row;
                }
            }
            if (Math.abs(rows.get(bestRow)[pivot]) < PIVOT_EPSILON) {
                return null;
            }
            double[] pivotRow = rows.get(bestRow);
            rows.set(bestRow, rows.get(pivot));
            rows.set(pivot, pivotRow);

            double divisor = rows.get(pivot)[pivot];
            for (int column = pivot; column < 4; column++) {
                rows.get(pivot)[column] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == pivot) {
                    continue;
                }
                double factor = rows.get(row)[pivot];
                for (int column = pivot; column < 4; column++) {
                    rows.get(row)[column] -= factor * rows.get(pivot)[column];
                }
            }
        }
        return new double[]{rows.get(0)[3], rows.get(1)[3], rows.get(2)[3]};
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public record Sample(double centerX, double centerY, double pan, double tilt) {
    }

    public record AxisModel(
            double intercept,
            double xCoefficient,
            double yCoefficient,
            double rmse) {
        public double predict(double centerX, double centerY) {
            return intercept + xCoefficient * centerX + yCoefficient * centerY;
        }
    }

    public record Model(AxisModel pan, AxisModel tilt) {
        public Pose predict(double centerX, double centerY) {
            return new Pose(pan.predict(centerX, centerY), tilt.predict(centerX, centerY));
        }
    }

    public record Pose(double pan, double tilt) {
    }

    public record FitResult(
            boolean ready,
            String reason,
            double xCoverage,
            double yCoverage,
            Model model) {
    }

    private enum Axis {
        PAN {
            @Override
            double value(Sample sample) {
                return sample.pan();
            }
        },
        TILT {
            @Override
            double value(Sample sample) {
                return sample.tilt();
            }
        };

        abstract double value(Sample sample);
    }
}
