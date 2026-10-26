package com.robotrac.recruitment.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** One consistent rule for emails everywhere: trim, lower-case, then validate. */
public final class EmailNormalizer {

    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,}$");

    private EmailNormalizer() {
    }

    /** Returns the normalised email, or null for null/blank input. */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.strip().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Validates an already-normalised email. */
    public static boolean isValid(String normalized) {
        return normalized != null
                && normalized.length() <= 254
                && !normalized.contains("..")
                && !normalized.startsWith(".")
                && EMAIL.matcher(normalized).matches();
    }
}
