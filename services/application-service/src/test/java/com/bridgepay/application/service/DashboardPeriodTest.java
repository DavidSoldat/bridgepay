package com.bridgepay.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29); // a Tuesday

    @Test
    void periodEndsTodayAndThePreviousOneEndsTheDayBefore() {
        DashboardPeriod p = DashboardPeriod.of(7, ZoneId.of("Europe/Belgrade"), TODAY);

        assertThat(p.tz()).isEqualTo("Europe/Belgrade");
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(p.to()).isEqualTo(TODAY);
        assertThat(p.previousFrom()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(p.previousTo()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(p.bucket()).isEqualTo("DAY");
        assertThat(p.unit()).isEqualTo("day");
        assertThat(DashboardPeriod.of(90, ZoneId.of("UTC"), TODAY).bucket()).isEqualTo("WEEK");
    }

    @Test
    void rejectsPeriodsOtherThan7_30_90() {
        assertThatThrownBy(() -> DashboardPeriod.of(14, ZoneId.of("UTC"), TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("days");
    }

    @Test
    void acceptsOnlyNamedTimeZones() {
        assertThat(DashboardPeriod.parseZone(null)).isEqualTo(ZoneId.of("UTC"));
        assertThat(DashboardPeriod.parseZone("Europe/Belgrade")).isEqualTo(ZoneId.of("Europe/Belgrade"));
        for (String bad : new String[] {"Mars/Base", "+02:00", "GMT+2", "Z", ""}) {
            assertThatThrownBy(() -> DashboardPeriod.parseZone(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fillsEveryDayWithTheRowOrNull() {
        DashboardPeriod p = DashboardPeriod.of(7, ZoneId.of("UTC"), TODAY);
        List<String> out = p.fill(Map.of(LocalDate.of(2026, 9, 25), "x"),
                (start, row) -> start.getDayOfMonth() + ":" + row);

        assertThat(out).containsExactly("23:null", "24:null", "25:x", "26:null", "27:null", "28:null", "29:null");
    }

    @Test
    void weeklyBucketsAreKeyedByMondayButTheFirstIsClampedToFrom() {
        DashboardPeriod p = DashboardPeriod.of(90, ZoneId.of("UTC"), TODAY); // from = Thu Jul 2
        List<String> out = p.fill(Map.of(LocalDate.of(2026, 6, 29), "first"), (start, row) -> start + ":" + row);

        assertThat(out).hasSize(14);
        assertThat(out.get(0)).isEqualTo("2026-07-02:first");
        assertThat(out.get(1)).isEqualTo("2026-07-06:null");
        assertThat(out.get(13)).isEqualTo("2026-09-28:null");
    }
}
