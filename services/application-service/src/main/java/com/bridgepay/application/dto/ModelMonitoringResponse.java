package com.bridgepay.application.dto;

import java.time.LocalDate;
import java.util.List;

/** Production side of model monitoring; the training side comes from credit-risk-engine's /api/v1/model. */
public record ModelMonitoringResponse(int days, LocalDate from, LocalDate to, Drift drift, Performance performance) {

    public record Drift(long scored, List<ScoreBin> scoreBins, List<Factor> factors) {
    }

    public record ScoreBin(double from, double to, long count) {
    }

    public record Factor(String feature, long count, double fireRate, double meanContribution) {
    }

    public record Performance(long finished, List<OutcomeBin> outcomeBins, Reviews reviews) {
    }

    public record OutcomeBin(double from, double to, long finished, long defaulted) {
    }

    public record Reviews(long decided, long agreedWithModel, Outcomes opsApproved, Outcomes modelApproved) {
    }

    public record Outcomes(long finished, long defaulted) {
    }
}
