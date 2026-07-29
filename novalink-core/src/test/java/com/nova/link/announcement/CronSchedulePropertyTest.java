package com.nova.link.announcement;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

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
}
