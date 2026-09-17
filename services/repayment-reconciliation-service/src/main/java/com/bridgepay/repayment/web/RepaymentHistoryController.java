package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.RepaymentHistoryResponse;
import com.bridgepay.repayment.service.RepaymentHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal-only: network isolation is the access control, same as Credit Risk Engine's /internal/score. */
@RestController
@RequestMapping("/internal/repayment-history")
public class RepaymentHistoryController {

    private final RepaymentHistoryService repaymentHistoryService;

    public RepaymentHistoryController(RepaymentHistoryService repaymentHistoryService) {
        this.repaymentHistoryService = repaymentHistoryService;
    }

    @GetMapping("/{applicantId}")
    public ResponseEntity<RepaymentHistoryResponse> getHistory(@PathVariable UUID applicantId) {
        return ResponseEntity.ok(repaymentHistoryService.getHistory(applicantId));
    }
}
