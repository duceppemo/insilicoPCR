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
}
