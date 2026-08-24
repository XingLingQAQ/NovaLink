package com.nova.link.announcement;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for CronSchedule correctness.
 * 
 * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
 * 
 * Tests that for any valid cron expression, the next execution time is correctly calculated.
 * 
 * **Validates: Requirements 22.1**
 */
public class CronSchedulePropertyTest {

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid cron expression with specific minute, parsing should succeed
     * and the period should be at least 1 hour (hourly execution).
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void validCronExpressionWithSpecificMinuteParsesCorrectly(
            @ForAll @IntRange(min = 0, max = 59) int minute
    ) {
        String expression = minute + " * * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Hourly execution = 60 * 60 * 1000 ms
        assertThat(schedule.getPeriodMs()).isEqualTo(60 * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid cron expression with specific hour, parsing should succeed
     * and the period should be 24 hours (daily execution).
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void validCronExpressionWithSpecificHourParsesCorrectly(
            @ForAll @IntRange(min = 0, max = 59) int minute,
            @ForAll @IntRange(min = 0, max = 23) int hour
    ) {
        String expression = minute + " " + hour + " * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Daily execution = 24 * 60 * 60 * 1000 ms
        assertThat(schedule.getPeriodMs()).isEqualTo(24 * 60 * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid cron expression with specific day of week, parsing should succeed
     * and the period should be 7 days (weekly execution).
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void validCronExpressionWithSpecificDayOfWeekParsesCorrectly(
            @ForAll @IntRange(min = 0, max = 59) int minute,
            @ForAll @IntRange(min = 0, max = 23) int hour,
            @ForAll @IntRange(min = 0, max = 6) int dayOfWeek
    ) {
        String expression = minute + " " + hour + " * * " + dayOfWeek;
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Weekly execution = 7 * 24 * 60 * 60 * 1000 ms
        assertThat(schedule.getPeriodMs()).isEqualTo(7 * 24 * 60 * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid step expression in minutes, parsing should succeed
     * and the period should be step * 60 * 1000 ms.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void validStepMinuteExpressionParsesCorrectly(
            @ForAll @IntRange(min = 1, max = 59) int step
    ) {
        String expression = "*/" + step + " * * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Step minutes = step * 60 * 1000 ms
        assertThat(schedule.getPeriodMs()).isEqualTo(step * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid step expression in hours, parsing should succeed
     * and the period should be step * 60 * 60 * 1000 ms.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void validStepHourExpressionParsesCorrectly(
            @ForAll @IntRange(min = 1, max = 23) int step
    ) {
        String expression = "0 */" + step + " * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Step hours = step * 60 * 60 * 1000 ms
        assertThat(schedule.getPeriodMs()).isEqualTo(step * 60 * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid cron expression, getNextExecutionDelay should return a positive value.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void nextExecutionDelayIsAlwaysPositive(
            @ForAll @IntRange(min = 0, max = 59) int minute
    ) {
        String expression = minute + " * * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        long delay = schedule.getNextExecutionDelay();
        
        // Delay should always be positive (at least MIN_PERIOD_MS = 60000)
        assertThat(delay).isGreaterThan(0);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For any valid cron expression, the next execution delay should not exceed
     * the period plus a reasonable buffer.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void nextExecutionDelayDoesNotExceedPeriod(
            @ForAll @IntRange(min = 0, max = 59) int minute
    ) {
        String expression = minute + " * * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        long delay = schedule.getNextExecutionDelay();
        long period = schedule.getPeriodMs();
        
        // Delay should not exceed period (with some buffer for edge cases)
        assertThat(delay).isLessThanOrEqualTo(period + 60000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * For all wildcards expression, parsing should succeed with hourly period.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 10)
    void allWildcardsExpressionParsesCorrectly() {
        String expression = "* * * * *";
        
        CronSchedule schedule = CronSchedule.parse(expression);
        
        assertThat(schedule).isNotNull();
        assertThat(schedule.getExpression()).isEqualTo(expression);
        // Default to hourly when all wildcards
        assertThat(schedule.getPeriodMs()).isEqualTo(60 * 60 * 1000L);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * Invalid cron expressions with wrong number of fields should throw exception.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void invalidCronExpressionWithWrongFieldCountThrowsException(
            @ForAll @IntRange(min = 1, max = 4) int fieldCount
    ) {
        StringBuilder expression = new StringBuilder();
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) expression.append(" ");
            expression.append("*");
        }
        
        String finalExpression = expression.toString();
        
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CronSchedule.parse(finalExpression)
        );
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * Invalid minute values (outside 0-59) should throw exception.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void invalidMinuteValueThrowsException(
            @ForAll @IntRange(min = 60, max = 100) int minute
    ) {
        String expression = minute + " * * * *";
        
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CronSchedule.parse(expression)
        );
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * Invalid hour values (outside 0-23) should throw exception.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void invalidHourValueThrowsException(
            @ForAll @IntRange(min = 24, max = 50) int hour
    ) {
        String expression = "0 " + hour + " * * *";
        
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CronSchedule.parse(expression)
        );
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * Invalid day of week values (outside 0-6) should throw exception.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void invalidDayOfWeekValueThrowsException(
            @ForAll @IntRange(min = 7, max = 20) int dayOfWeek
    ) {
        String expression = "0 0 * * " + dayOfWeek;
        
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CronSchedule.parse(expression)
        );
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     * 
     * Null expression should throw NullPointerException.
     * 
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 10)
    void nullExpressionThrowsException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> CronSchedule.parse(null)
        );
    }

    /**
     * **Feature: novachat-platform-expansion, Property 13: Cron Schedule Correctness**
     *
     * toString should contain the original expression.
     *
     * **Validates: Requirements 22.1**
     */
    @Property(tries = 100)
    void toStringContainsExpression(
            @ForAll @IntRange(min = 0, max = 59) int minute
    ) {
        String expression = minute + " * * * *";

        CronSchedule schedule = CronSchedule.parse(expression);
        String toString = schedule.toString();

        assertThat(toString).contains(expression);
    }

    // ==================== BACK-005: per-fire next-delay tests ====================

    /**
     * Monthly schedule ("0 0 15 * *") must target the 15th of the next month
     * rather than now plus 30 days. With a reference time of Feb 1 2026
     * 00:00 UTC, the next fire is Feb 15 2026 00:00 (14 days), whereas the
     * buggy fixed-period approximation (30d) would land on Mar 3.
     */
    @Test
    void monthlyNextDelayTargetsNextWallClockDayNotPlus30Days() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(tz);
        now.set(2026, Calendar.FEBRUARY, 1, 0, 0, 0);
        now.set(Calendar.MILLISECOND, 0);

        CronSchedule schedule = CronSchedule.parse("0 0 15 * *");
        long delayMs = schedule.getNextExecutionDelay(now);

        // Feb 1 to Feb 15 = 14 days = 14 * 86_400_000 ms
        long fourteenDaysMs = 14L * 24 * 60 * 60 * 1000L;
        assertThat(delayMs).isEqualTo(fourteenDaysMs);
    }

    /**
     * Monthly schedule firing past the target day rolls to the next month,
     * preserving the day-of-month rather than using now plus 30 days.
     * Jan 16 2026 to Feb 15 2026 is 30 days here, but the mechanism is a
     * month-add, which matters for short months like Feb.
     */
    @Test
    void monthlyNextDelayFromPastTargetDayRollsToNextMonth() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(tz);
        now.set(2026, Calendar.JANUARY, 16, 0, 0, 0);
        now.set(Calendar.MILLISECOND, 0);

        CronSchedule schedule = CronSchedule.parse("0 0 15 * *");
        long delayMs = schedule.getNextExecutionDelay(now);

        // Jan 16 to Feb 15 = 30 days (Jan has 31, so 31-16 + 15 = 30)
        long expectedMs = 30L * 24 * 60 * 60 * 1000L;
        assertThat(delayMs).isEqualTo(expectedMs);
    }

    /**
     * Monthly schedule landing on Feb 15 from Jan 31 must give Feb 15
     * (15 days), not Mar 2 (30d). This is the bug the fixed-period
     * approximation would produce.
     */
    @Test
    void monthlyNextDelayAcrossShortMonthIsNotPlus30Days() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(tz);
        now.set(2026, Calendar.JANUARY, 31, 0, 0, 0);
        now.set(Calendar.MILLISECOND, 0);

        CronSchedule schedule = CronSchedule.parse("0 0 15 * *");
        long delayMs = schedule.getNextExecutionDelay(now);

        // Jan 31 to Feb 15 = 15 days (Feb has 28 days, so plus 30d would
        // overshoot to Mar 2). 15 * 86_400_000 ms.
        long fifteenDaysMs = 15L * 24 * 60 * 60 * 1000L;
        assertThat(delayMs).isEqualTo(fifteenDaysMs);
    }

    /**
     * Weekday-specific schedule ("0 12 * * 1" = Mondays at noon) targets
     * the next Monday at noon rather than now plus 7 days. With a
     * reference time of Wed Feb 4 2026 10:00 UTC, the next Monday is
     * Feb 9 2026 12:00.
     */
    @Test
    void weekdayNextDelayTargetsNextWallClockDayNotPlus7Days() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(tz);
        // Feb 4 2026 is a Wednesday; 10:00 local.
        now.set(2026, Calendar.FEBRUARY, 4, 10, 0, 0);
        now.set(Calendar.MILLISECOND, 0);

        // day-of-week: 0 = Sunday in this cron parser, so 1 = Monday.
        CronSchedule schedule = CronSchedule.parse("0 12 * * 1");
        long delayMs = schedule.getNextExecutionDelay(now);

        // Wed Feb 4 10:00 to Mon Feb 9 12:00 = 5 days 2 hours.
        long expectedMs = (5L * 24 * 60 * 60 + 2 * 60 * 60) * 1000L;
        assertThat(delayMs).isEqualTo(expectedMs);
    }

    /**
     * Hourly schedule ("0 * * * *") at a reference time of 10:30 must
     * target 11:00 (30 minutes), not now plus 1h (which would be 11:30).
     * This is what keeps hourly schedules firing on the wall-clock hour
     * across DST.
     */
    @Test
    void hourlyNextDelayTargetsWallClockHourNotPlusPeriod() {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        Calendar now = Calendar.getInstance(tz);
        now.set(2026, Calendar.FEBRUARY, 4, 10, 30, 0);
        now.set(Calendar.MILLISECOND, 0);

        CronSchedule schedule = CronSchedule.parse("0 * * * *");
        long delayMs = schedule.getNextExecutionDelay(now);

        // 10:30 to 11:00 = 30 minutes = 30 * 60 * 1000 ms
        long thirtyMinMs = 30L * 60 * 1000L;
        assertThat(delayMs).isEqualTo(thirtyMinMs);
    }

    /**
     * DST-hourly recompute: in a timezone that observes DST
     * (America/New_York), an hourly cron ("0 * * * *") at 01:30 local on
     * the spring-forward boundary (the hour 02:00-02:59 does not exist)
     * must still target a valid wall-clock time. Calendar.add of one
     * hour is DST-aware and lands on 03:00 EDT (the first valid hour
     * after the skip), so the real elapsed delay is 30 minutes.
     */
    @Test
    void hourlyNextDelayAcrossDSTSpringForwardRecomputes() {
        TimeZone nyTz = TimeZone.getTimeZone("America/New_York");
        // US DST 2026 starts Sun Mar 8 2026. At 01:30 NY the 02:00 hour
        // has not yet been skipped; the next whole hour is 03:00 EDT.
        Calendar now = Calendar.getInstance(nyTz);
        now.set(2026, Calendar.MARCH, 8, 1, 30, 0);
        now.set(Calendar.MILLISECOND, 0);

        CronSchedule schedule = CronSchedule.parse("0 * * * *");
        long delayMs = schedule.getNextExecutionDelay(now);

        // 01:30 EST to 03:00 EDT is exactly 30 minutes of real time (the
        // 02:00 hour vanishes). 30 * 60 * 1000 ms.
        long thirtyMinMs = 30L * 60 * 1000L;
        assertThat(delayMs).isEqualTo(thirtyMinMs);
    }
}
