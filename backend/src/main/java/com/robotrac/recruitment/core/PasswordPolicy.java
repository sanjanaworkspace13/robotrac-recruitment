package com.robotrac.recruitment.core;

import java.util.ArrayList;
import java.util.List;

/** Password strength rules. Returns a list of problems (empty = acceptable). */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** HR/Admin accounts: at least 12 characters with upper case, lower case, digit and symbol. */
    public static List<String> checkAdminPassword(String password) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < 12) {
            problems.add("Password must be at least 12 characters long.");
            return problems;
        }
        if (password.length() > 128) {
            problems.add("Password must be at most 128 characters long.");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            problems.add("Password must contain an upper-case letter.");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            problems.add("Password must contain a lower-case letter.");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            problems.add("Password must contain a digit.");
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            problems.add("Password must contain a symbol (for example ! @ # $ %).");
        }
        return problems;
    }

    /** Shared drive passwords: at least 8 characters containing letters and digits. */
    public static List<String> checkDrivePassword(String password) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < 8) {
            problems.add("Drive password must be at least 8 characters long.");
            return problems;
        }
        if (password.length() > 64) {
            problems.add("Drive password must be at most 64 characters long.");
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            problems.add("Drive password must contain both letters and digits.");
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            problems.add("Drive password must not contain spaces.");
        }
        return problems;
    }
}
