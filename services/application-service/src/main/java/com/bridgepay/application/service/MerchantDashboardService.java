package com.bridgepay.application.service;

import com.bridgepay.application.dto.MerchantDashboardResponse;
import com.bridgepay.application.dto.MerchantDashboardResponse.PeriodTotals;
import com.bridgepay.application.dto.MerchantDashboardResponse.SeriesPoint;
import com.bridgepay.application.repository.MerchantDashboardQueries;
import com.bridgepay.application.repository.MerchantDashboardQueries.BucketRow;
import com.bridgepay.application.repository.MerchantDashboardQueries.PaidRow;
import com.bridgepay.application.repository.MerchantDashboardQueries.StatusRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MerchantDashboardService {

    private static final Set<String> APPROVED = Set.of("APPROVED", "COMPLETED", "DEFAULTED");

    private final MerchantDashboardQueries queries;

    public MerchantDashboardService(MerchantDashboardQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public MerchantDashboardResponse dashboard(UUID merchantId, int days, String tz) {
        ZoneId zone = DashboardPeriod.parseZone(tz);
        return dashboard(merchantId, days, zone, LocalDate.now(zone));
    }

    MerchantDashboardResponse dashboard(UUID merchantId, int days, ZoneId zone, LocalDate today) {
        DashboardPeriod p = DashboardPeriod.of(days, zone, today);
        return new MerchantDashboardResponse(days, p.from(), p.to(), p.bucket(),
                totals(merchantId, p.tz(), p.from(), p.to()),
                totals(merchantId, p.tz(), p.previousFrom(), p.previousTo()),
                money(queries.pendingNet(merchantId)),
                series(merchantId, p));
    }

    private PeriodTotals totals(UUID merchantId, String tz, LocalDate from, LocalDate to) {
        long checkouts = 0, approved = 0, declined = 0, inReview = 0, paid = 0;
        BigDecimal volume = BigDecimal.ZERO;
        for (StatusRow row : queries.statusTotals(merchantId, tz, from, to)) {
            checkouts += row.count();
            paid += row.paid();
            if (APPROVED.contains(row.status())) {
                approved += row.count();
                volume = volume.add(row.volume());
            } else if ("DECLINED".equals(row.status())) {
                declined = row.count();
            } else if ("MANUAL_REVIEW".equals(row.status())) {
                inReview = row.count();
            }
        }
        Double approvalRate = approved + declined == 0 ? null : (double) approved / (approved + declined);
        PaidRow payouts = queries.paidPayouts(merchantId, tz, from, to);
        return new PeriodTotals(checkouts, approved, declined, inReview, paid, money(volume), approvalRate,
                money(payouts.fees()), money(payouts.net()));
    }

    private List<SeriesPoint> series(UUID merchantId, DashboardPeriod p) {
        Map<LocalDate, BucketRow> rows = queries.buckets(merchantId, p.tz(), p.from(), p.to(), p.unit()).stream()
                .collect(Collectors.toMap(BucketRow::start, Function.identity()));
        return p.fill(rows, (start, row) -> new SeriesPoint(start,
                row == null ? 0 : row.checkouts(),
                money(row == null ? BigDecimal.ZERO : row.approvedVolume())));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
