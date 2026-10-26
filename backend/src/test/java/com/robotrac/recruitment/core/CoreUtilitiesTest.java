package com.robotrac.recruitment.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CoreUtilitiesTest {

    @Test
    void emailsAreTrimmedLowerCasedAndValidated() {
        assertThat(EmailNormalizer.normalize("  Priya.Sharma@Example.COM ")).isEqualTo("priya.sharma@example.com");
        assertThat(EmailNormalizer.isValid("priya.sharma@example.com")).isTrue();
        assertThat(EmailNormalizer.isValid("not-an-email")).isFalse();
        assertThat(EmailNormalizer.isValid("a..b@example.com")).isFalse();
        assertThat(EmailNormalizer.isValid("a@b")).isFalse();
        assertThat(EmailNormalizer.normalize("   ")).isNull();
    }

    @Test
    void passwordPolicies() {
        assertThat(PasswordPolicy.checkAdminPassword("Str0ng!Passw0rd")).isEmpty();
        assertThat(PasswordPolicy.checkAdminPassword("short")).isNotEmpty();
        assertThat(PasswordPolicy.checkAdminPassword("alllowercase123!")).isNotEmpty();
        assertThat(PasswordPolicy.checkDrivePassword("Drive2026pass")).isEmpty();
        assertThat(PasswordPolicy.checkDrivePassword("onlyletters")).isNotEmpty();
    }

    @Test
    void csvParsingHandlesQuotesAndBlankLines() {
        List<List<String>> rows = CsvUtil.parse("﻿email,name\r\na@x.com,\"Doe, Jane\"\n\nb@x.com\n");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1)).containsExactly("a@x.com", "Doe, Jane");
        assertThat(rows.get(2)).isEmpty();
        assertThat(rows.get(3)).containsExactly("b@x.com");
    }

    @Test
    void csvExportEscapesFormulaInjection() {
        assertThat(CsvUtil.escape("=HYPERLINK(\"http://evil\")")).isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(CsvUtil.escape("+1")).isEqualTo("'+1");
        assertThat(CsvUtil.escape(-5)).isEqualTo("-5");
        assertThat(CsvUtil.escape("plain")).isEqualTo("plain");
    }

    @Test
    void optionOrderMapsDisplayIndexesToLetters() {
        String order = OptionOrder.random(new Random(1));
        assertThat(OptionOrder.isValid(order)).isTrue();
        for (int i = 0; i < 4; i++) {
            char letter = OptionOrder.letterAt(order, i);
            assertThat(OptionOrder.displayIndexOf(order, String.valueOf(letter))).isEqualTo(i);
        }
        assertThat(OptionOrder.displayIndexOf(order, null)).isNull();
        assertThat(OptionOrder.isValid("AABC")).isFalse();
    }

    @Test
    void rateLimiterBlocksAfterLimitAndResetsAfterWindow() {
        MutableTestClock clock = new MutableTestClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.isBlocked("k", 3, Duration.ofMinutes(1))).isTrue();
        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isTrue();
        limiter.reset("k");
        assertThat(limiter.isBlocked("k", 1, Duration.ofMinutes(1))).isFalse();
    }

    private static final class MutableTestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
