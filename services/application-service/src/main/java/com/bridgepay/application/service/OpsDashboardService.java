package com.bridgepay.application.service;

import com.bridgepay.application.dto.OpsDashboardResponse;
import com.bridgepay.application.dto.OpsDashboardResponse.PeriodTotals;
import com.bridgepay.application.dto.OpsDashboardResponse.Queue;
import com.bridgepay.application.dto.OpsDashboardResponse.Reviewer;
import com.bridgepay.application.dto.OpsDashboardResponse.ScoreBin;
import com.bridgepay.application.dto.OpsDashboardResponse.SeriesPoint;
import com.bridgepay.application.repository.OpsDashboardQueries;
import com.bridgepay.application.repository.OpsDashboardQueries.BinRow;
import com.bridgepay.application.repository.OpsDashboardQueries.BucketRow;
import com.bridgepay.application.repository.OpsDashboardQueries.QueueRow;
import com.bridgepay.application.repository.OpsDashboardQueries.TotalsRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class OpsDashboardService {

    private final OpsDashboardQueries queries;

    public OpsDashboardService(OpsDashboardQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public OpsDashboardResponse dashboard(int days, String tz) {
        ZoneId zone = DashboardPeriod.parseZone(tz);
        return dashboard(days, zone, LocalDate.now(zone));
    }

    OpsDashboardResponse dashboard(int days, ZoneId zone, LocalDate today) {
        DashboardPeriod p = DashboardPeriod.of(days, zone, today);
        QueueRow queue = queries.queue();
        return new OpsDashboardResponse(days, p.from(), p.to(), p.bucket(),
                new Queue(queue.inReview(), queue.oldest()),
                totals(queries.totals(p.tz(), p.from(), p.to())),
                totals(queries.totals(p.tz(), p.previousFrom(), p.previousTo())),
                series(p),
                histogram(p),
                queries.reviewers(p.tz(), p.from(), p.to()).stream()
                        .map(r -> new Reviewer(r.name(), r.decisions(), r.approved(), r.declined(), r.medianReviewSeconds()))
                        .toList());
    }

    private static PeriodTotals totals(TotalsRow r) {
        Double approvalRate = r.approved() + r.declined() == 0 ? null : (double) r.approved() / (r.approved() + r.declined());
        return new PeriodTotals(r.applications(), r.autoApproved() + r.autoDeclined(), r.reviewed(), r.waiting(),
                r.approved(), r.declined(), approvalRate, r.medianReviewSeconds());
    }

    private List<SeriesPoint> series(DashboardPeriod p) {
        Map<LocalDate, BucketRow> rows = queries.buckets(p.tz(), p.from(), p.to(), p.unit()).stream()
                .collect(Collectors.toMap(BucketRow::start, Function.identity()));
        return p.fill(rows, (start, row) -> row == null
                ? new SeriesPoint(start, 0, 0, 0)
                : new SeriesPoint(start, row.autoApproved(), row.autoDeclined(), row.review()));
    }

    private List<ScoreBin> histogram(DashboardPeriod p) {
        Map<Integer, Long> counts = queries.scoreBins(p.tz(), p.from(), p.to()).stream()
                .collect(Collectors.toMap(BinRow::bin, BinRow::count));
        return IntStream.range(0, 10)
                .mapToObj(bin -> new ScoreBin(bin / 10.0, (bin + 1) / 10.0, counts.getOrDefault(bin, 0L)))
                .toList();
    }
}
