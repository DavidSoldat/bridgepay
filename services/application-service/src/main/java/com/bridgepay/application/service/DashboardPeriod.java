package com.bridgepay.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A dashboard period: the {@code days} local dates ending today in {@code tz} (inclusive), the same-length
 * period before it, and its series buckets (days for 7/30, ISO weeks for 90). Shared by the merchant and ops
 * dashboards.
 */
public record DashboardPeriod(int days, String tz, LocalDate from, LocalDate to,
                              LocalDate previousFrom, LocalDate previousTo, boolean weekly) {

    private static final Set<Integer> PERIODS = Set.of(7, 30, 90);

    public static DashboardPeriod of(int days, ZoneId zone, LocalDate today) {
        if (!PERIODS.contains(days)) {
            throw new IllegalArgumentException("days must be 7, 30 or 90");
        }
        LocalDate from = today.minusDays(days - 1L);
        return new DashboardPeriod(days, zone.getId(), from, today, from.minusDays(days), from.minusDays(1), days == 90);
    }

    /**
     * Only tz database names: Postgres reads POSIX-style offsets ("+02:00", "GMT+2") with the sign flipped,
     * so accepting them would silently shift every bucket. Missing means UTC.
     */
    public static ZoneId parseZone(String tz) {
        if (tz == null) {
            return ZoneId.of("UTC");
        }
        if (!ZoneId.getAvailableZoneIds().contains(tz)) {
            throw new IllegalArgumentException("tz must be a time zone name like Europe/Belgrade");
        }
        return ZoneId.of(tz);
    }

    public String bucket() {
        return weekly ? "WEEK" : "DAY";
    }

    /** The {@code date_trunc} unit matching {@link #bucket()}. */
    public String unit() {
        return weekly ? "week" : "day";
    }

    /**
     * One point per bucket of the current period, ascending, empty buckets included. {@code rows} is keyed by
     * bucket start as {@code date_trunc} returns it (a Monday for weeks); the first week's start is clamped to
     * {@code from} before it's handed to {@code point}. The row is null for an empty bucket.
     */
    public <R, P> List<P> fill(Map<LocalDate, R> rows, BiFunction<LocalDate, R, P> point) {
        List<P> out = new ArrayList<>();
        LocalDate start = weekly ? from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : from;
        for (LocalDate b = start; !b.isAfter(to); b = weekly ? b.plusWeeks(1) : b.plusDays(1)) {
            out.add(point.apply(b.isBefore(from) ? from : b, rows.get(b)));
        }
        return out;
    }
}
