package ca.canada.inspection.util;

import java.math.BigDecimal;

/** Numbers as messages and settings show them. */
public final class Numbers {

    private Numbers() {
    }

    /** {@code value} as a person writes it, without trailing zeros or an exponent: {@code 60}, {@code 58.5}, {@code 0.8}. */
    public static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** @throws IllegalArgumentException when {@code value} is negative, named {@code label} (an option's name) */
    public static void requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be >= 0, got: " + value);
        }
    }

    /** @throws IllegalArgumentException when {@code value} is negative or not a number, named {@code label} */
    public static void requireNonNegative(double value, String label) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be >= 0, got: " + shown(value));
        }
    }

    /**
     * {@code value} as a message shows it: {@link #plain} ({@code -1}, {@code 0.8}) unless that would be long, for a
     * very large or very small magnitude, or is not a number ({@code -1.0E300}, {@code NaN}).
     */
    public static String shown(double value) {
        double size = Math.abs(value);
        return Double.isFinite(value) && size < 1e9 && (size >= 1e-6 || size == 0) ? plain(value)
                : String.valueOf(value);
    }
}
