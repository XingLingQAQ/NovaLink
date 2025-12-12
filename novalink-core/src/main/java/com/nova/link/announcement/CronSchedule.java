package com.nova.link.announcement;

import java.util.Calendar;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple Cron schedule parser for scheduled announcements.
 * Supports a simplified cron format: minute hour day-of-month month day-of-week
 * 
 * Supported values:
 * - Specific numbers (e.g., 30 for minute 30)
 * - Wildcards (*) for every
 * - Step values (e.g., star/5 for every 5 units)
 * 
 * Examples:
 * - 0 * * * * - Every hour at minute 0
 * - star/30 * * * * - Every 30 minutes
 * - 0 12 * * * - Every day at 12:00
 * - 0 0 * * 0 - Every Sunday at midnight
 * 
 * Requirements: 14.2
 */
public class CronSchedule {

    /** Pattern for step values like star/5 */
    private static final Pattern STEP_PATTERN = Pattern.compile("\\*/([0-9]+)");

    /** Minimum period in milliseconds (1 minute) */
    private static final long MIN_PERIOD_MS = 60 * 1000L;

    /** Maximum period in milliseconds (1 week) */
    private static final long MAX_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L;

    private final String expression;
    private final int minute;
    private final int hour;
    private final int dayOfMonth;
    private final int month;
    private final int dayOfWeek;
    private final long periodMs;

    // -1 means wildcard (*)
    private static final int WILDCARD = -1;

    private CronSchedule(String expression, int minute, int hour, int dayOfMonth, 
                         int month, int dayOfWeek, long periodMs) {
        this.expression = expression;
        this.minute = minute;
        this.hour = hour;
        this.dayOfMonth = dayOfMonth;
        this.month = month;
        this.dayOfWeek = dayOfWeek;
        this.periodMs = periodMs;
    }

    /**
     * Parses a cron expression.
     *
     * @param expression the cron expression (5 fields: minute hour day month weekday)
     * @return the parsed schedule
     * @throws IllegalArgumentException if the expression is invalid
     */
    public static CronSchedule parse(String expression) {
        Objects.requireNonNull(expression, "Cron expression cannot be null");
        
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields (minute hour day month weekday)");
        }

        int minute = parseField(parts[0], 0, 59, "minute");
        int hour = parseField(parts[1], 0, 23, "hour");
        int dayOfMonth = parseField(parts[2], 1, 31, "day of month");
        int month = parseField(parts[3], 1, 12, "month");
        int dayOfWeek = parseField(parts[4], 0, 6, "day of week");

        // Calculate period based on the most specific non-wildcard field
        long periodMs = calculatePeriod(parts, minute, hour, dayOfMonth, month, dayOfWeek);

        return new CronSchedule(expression, minute, hour, dayOfMonth, month, dayOfWeek, periodMs);
    }

    /**
     * Parses a single cron field.
     *
     * @param field the field value
     * @param min minimum allowed value
     * @param max maximum allowed value
     * @param name field name for error messages
     * @return the parsed value, or WILDCARD for star
     */
    private static int parseField(String field, int min, int max, String name) {
        if ("*".equals(field)) {
            return WILDCARD;
        }

        // Check for step pattern (star/n)
        Matcher stepMatcher = STEP_PATTERN.matcher(field);
        if (stepMatcher.matches()) {
            int step = Integer.parseInt(stepMatcher.group(1));
            if (step <= 0 || step > (max - min + 1)) {
                throw new IllegalArgumentException(
                        "Invalid step value for " + name + ": " + step);
            }
            // Return negative step value to indicate step pattern
            return -step - 10; // Encode step as negative value
        }

        try {
            int value = Integer.parseInt(field);
            if (value < min || value > max) {
                throw new IllegalArgumentException(
                        name + " must be between " + min + " and " + max + ", got: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + " value: " + field);
        }
    }

    /**
     * Calculates the period in milliseconds based on the cron expression.
     */
    private static long calculatePeriod(String[] parts, int minute, int hour, 
                                         int dayOfMonth, int month, int dayOfWeek) {
        // Check for step patterns first
        Matcher minuteStep = STEP_PATTERN.matcher(parts[0]);
        if (minuteStep.matches()) {
            int step = Integer.parseInt(minuteStep.group(1));
            return step * 60 * 1000L; // Every N minutes
        }

        Matcher hourStep = STEP_PATTERN.matcher(parts[1]);
        if (hourStep.matches()) {
            int step = Integer.parseInt(hourStep.group(1));
            return step * 60 * 60 * 1000L; // Every N hours
        }

        // Determine period based on most specific field
        if (dayOfWeek != WILDCARD && dayOfWeek >= 0) {
            return 7 * 24 * 60 * 60 * 1000L; // Weekly
        }
        if (dayOfMonth != WILDCARD && dayOfMonth >= 0) {
            return 30 * 24 * 60 * 60 * 1000L; // Monthly (approximate)
        }
        if (hour != WILDCARD && hour >= 0) {
            return 24 * 60 * 60 * 1000L; // Daily
        }
        if (minute != WILDCARD && minute >= 0) {
            return 60 * 60 * 1000L; // Hourly
        }

        // Default to hourly if all wildcards
        return 60 * 60 * 1000L;
    }

    /**
     * Gets the delay until the next execution in milliseconds.
     *
     * @return delay in milliseconds, or -1 if cannot be calculated
     */
    public long getNextExecutionDelay() {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();

        // Set the next execution time based on cron fields
        if (minute >= 0) {
            next.set(Calendar.MINUTE, minute);
        }
        if (hour >= 0) {
            next.set(Calendar.HOUR_OF_DAY, hour);
        }
        if (dayOfMonth >= 0) {
            next.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        }
        if (month >= 0) {
            next.set(Calendar.MONTH, month - 1); // Calendar months are 0-based
        }
        if (dayOfWeek >= 0) {
            // Adjust to next occurrence of the day of week
            int currentDow = now.get(Calendar.DAY_OF_WEEK) - 1; // Convert to 0-based
            int daysUntil = (dayOfWeek - currentDow + 7) % 7;
            if (daysUntil == 0 && next.before(now)) {
                daysUntil = 7;
            }
            next.add(Calendar.DAY_OF_MONTH, daysUntil);
        }

        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        // If the calculated time is in the past, add the period
        if (next.before(now) || next.equals(now)) {
            next.setTimeInMillis(now.getTimeInMillis() + periodMs);
        }

        long delay = next.getTimeInMillis() - now.getTimeInMillis();
        return Math.max(delay, MIN_PERIOD_MS);
    }

    /**
     * Gets the period in milliseconds between executions.
     *
     * @return period in milliseconds
     */
    public long getPeriodMs() {
        return periodMs;
    }

    /**
     * Gets the original cron expression.
     *
     * @return the cron expression
     */
    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return "CronSchedule{" +
                "expression='" + expression + '\'' +
                ", periodMs=" + periodMs +
                '}';
    }
}
