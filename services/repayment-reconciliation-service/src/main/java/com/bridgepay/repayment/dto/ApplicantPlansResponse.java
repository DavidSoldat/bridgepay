package com.bridgepay.repayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Ops: every plan of one shopper plus the same history counts the credit-risk overlay scores. */
public record ApplicantPlansResponse(RepaymentHistoryResponse history, List<Plan> plans) {

    public record Plan(UUID planId, UUID applicationId, String status, BigDecimal totalAmount, int installmentCount,
                       BigDecimal installmentAmount, Instant createdAt, Instant updatedAt,
                       List<Installment> installments) {
    }

    public record Installment(int sequenceNumber, LocalDate dueDate, BigDecimal amount, String status,
                              Instant paidAt, Instant updatedAt) {
    }
}
