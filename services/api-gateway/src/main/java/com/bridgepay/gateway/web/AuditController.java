package com.bridgepay.gateway.web;

import com.bridgepay.gateway.audit.AuditEntryRepository;
import com.bridgepay.gateway.audit.AuditEntryResponse;
import com.bridgepay.gateway.audit.AuditQuery;
import com.bridgepay.gateway.audit.InvalidAuditQueryException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ops-only search over the gateway's own audit log. Served here, not proxied: no route covers /api/v1/audit. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int PAGE_SIZE = 50;

    private final AuditEntryRepository repository;

    public AuditController(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPS')")
    public Page<AuditEntryResponse> list(@RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) String targetId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String tz,
                                         @RequestParam(required = false) String outcome,
                                         @RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new InvalidAuditQueryException("page must be 0 or more");
        AuditQuery query = AuditQuery.parse(actor, action, targetType, targetId, from, to, tz, outcome);
        PageRequest pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return repository.findAll(query.toSpecification(), pageable).map(AuditEntryResponse::of);
    }
}
