package com.bridgepay.creditbureau.web;

import com.bridgepay.creditbureau.pool.BureauPool;
import com.bridgepay.creditbureau.pool.BureauProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal-only - never routed through the gateway, unreachable from outside
 * the cluster at the network level (spec section 11).
 */
@RestController
public class BureauProfileController {

    private final BureauPool bureauPool;

    public BureauProfileController(BureauPool bureauPool) {
        this.bureauPool = bureauPool;
    }

    @GetMapping("/internal/bureau-profile/{applicantId}")
    public BureauProfile bureauProfile(@PathVariable UUID applicantId) {
        return bureauPool.lookup(applicantId);
    }
}
