package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.FailedEventResponse;
import com.bridgepay.repayment.service.FailedEventService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops/failed-events")
public class FailedEventController {

    private final FailedEventService failedEventService;

    public FailedEventController(FailedEventService failedEventService) {
        this.failedEventService = failedEventService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPS')")
    public Page<FailedEventResponse> list(@RequestParam(defaultValue = "FAILED") String status, Pageable pageable) {
        return failedEventService.list(status, pageable);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('OPS')")
    public FailedEventResponse retry(@PathVariable UUID id) {
        return failedEventService.retry(id);
    }
}
