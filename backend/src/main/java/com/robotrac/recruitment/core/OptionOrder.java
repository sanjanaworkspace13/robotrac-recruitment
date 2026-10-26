package com.robotrac.recruitment.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Option shuffling. An option order is a permutation of "ABCD": display index i shows original option
 * {@code order.charAt(i)}. The browser works only with display indexes 0-3.
 */
public final class OptionOrder {

    public static final String IDENTITY = "ABCD";

    private OptionOrder() {
    }

    public static String random(Random random) {
        List<Character> letters = new ArrayList<>(List.of('A', 'B', 'C', 'D'));
        Collections.shuffle(letters, random);
        StringBuilder sb = new StringBuilder(4);
        letters.forEach(sb::append);
        return sb.toString();
    }

    public static boolean isValid(String order) {
        if (order == null || order.length() != 4) {
            return false;
        }
        return order.chars().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().equals(IDENTITY);
    }

    /** Display index (0-3) to original letter. */
    public static char letterAt(String order, int displayIndex) {
        if (displayIndex < 0 || displayIndex > 3) {
            throw new IllegalArgumentException("Option index must be between 0 and 3");
        }
        return order.charAt(displayIndex);
    }

    /** Original letter to display index, or null for null. */
    public static Integer displayIndexOf(String order, String letter) {
        if (letter == null || letter.isEmpty()) {
            return null;
        }
        int idx = order.indexOf(Character.toUpperCase(letter.charAt(0)));
        return idx < 0 ? null : idx;
    }
}
