package com.bridgepay.application.service;

import com.bridgepay.application.dto.ModelMonitoringResponse;
import com.bridgepay.application.dto.ModelMonitoringResponse.Drift;
import com.bridgepay.application.dto.ModelMonitoringResponse.Factor;
import com.bridgepay.application.dto.ModelMonitoringResponse.OutcomeBin;
import com.bridgepay.application.dto.ModelMonitoringResponse.Outcomes;
import com.bridgepay.application.dto.ModelMonitoringResponse.Performance;
import com.bridgepay.application.dto.ModelMonitoringResponse.Reviews;
import com.bridgepay.application.dto.ModelMonitoringResponse.ScoreBin;
import com.bridgepay.application.repository.ModelMonitoringQueries;
import com.bridgepay.application.repository.ModelMonitoringQueries.BinRow;
import com.bridgepay.application.repository.ModelMonitoringQueries.OutcomeRow;
import com.bridgepay.application.repository.ModelMonitoringQueries.ReviewRow;
import com.bridgepay.application.repository.ModelMonitoringQueries.SourceOutcomeRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ModelMonitoringService {

    private final ModelMonitoringQueries queries;

    public ModelMonitoringService(ModelMonitoringQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public ModelMonitoringResponse monitoring(int days, String tz) {
        ZoneId zone = DashboardPeriod.parseZone(tz);
        DashboardPeriod p = DashboardPeriod.of(days, zone, LocalDate.now(zone));
        return new ModelMonitoringResponse(days, p.from(), p.to(), drift(p), performance(p));
    }

    private Drift drift(DashboardPeriod p) {
        long scored = queries.scored(p.tz(), p.from(), p.to());
        Map<Integer, Long> counts = queries.scoreBins(p.tz(), p.from(), p.to()).stream()
                .collect(Collectors.toMap(BinRow::bin, BinRow::count));
        List<ScoreBin> bins = IntStream.range(0, 10)
                .mapToObj(b -> new ScoreBin(b / 10.0, (b + 1) / 10.0, counts.getOrDefault(b, 0L))).toList();
        List<Factor> factors = queries.factors(p.tz(), p.from(), p.to()).stream()
                .map(f -> new Factor(f.feature(), f.count(), scored == 0 ? 0 : (double) f.count() / scored, f.mean()))
                .sorted(Comparator.comparingDouble((Factor f) -> Math.abs(f.meanContribution())).reversed())
                .toList();
        return new Drift(scored, bins, factors);
    }

    private Performance performance(DashboardPeriod p) {
        Map<Integer, OutcomeRow> rows = queries.outcomes(p.tz(), p.from(), p.to()).stream()
                .collect(Collectors.toMap(OutcomeRow::bin, Function.identity()));
        List<OutcomeBin> bins = IntStream.range(0, 10).mapToObj(b -> {
            OutcomeRow r = rows.get(b);
            return new OutcomeBin(b / 10.0, (b + 1) / 10.0, r == null ? 0 : r.finished(), r == null ? 0 : r.defaulted());
        }).toList();
        long finished = bins.stream().mapToLong(OutcomeBin::finished).sum();
        ReviewRow reviews = queries.reviews(p.tz(), p.from(), p.to());
        Map<String, SourceOutcomeRow> bySource = queries.outcomesBySource(p.tz(), p.from(), p.to()).stream()
                .collect(Collectors.toMap(SourceOutcomeRow::source, Function.identity()));
        return new Performance(finished, bins, new Reviews(reviews.decided(), reviews.agreed(),
                outcomes(bySource.get("OPS")), outcomes(bySource.get("MODEL"))));
    }

    private static Outcomes outcomes(SourceOutcomeRow r) {
        return r == null ? new Outcomes(0, 0) : new Outcomes(r.finished(), r.defaulted());
    }
}
