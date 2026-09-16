package com.bridgepay.creditbureau.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BureauPoolTest {

    private BureauPool pool;

    @BeforeEach
    void setUp() {
        pool = new BureauPool();
        pool.load();
    }

    @Test
    void load_readsTheFullGeneratedPool() {
        // 15% of the raw 150k-row dataset - see data/README.md's split rule.
        assertThat(pool.size()).isEqualTo(22_500);
    }

    @Test
    void lookup_isDeterministic_forTheSameApplicant() {
        UUID applicantId = UUID.randomUUID();

        BureauProfile first = pool.lookup(applicantId);
        BureauProfile second = pool.lookup(applicantId);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void lookup_returnsDifferentProfiles_acrossDifferentApplicants() {
        Set<BureauProfile> distinctProfiles = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            distinctProfiles.add(pool.lookup(UUID.randomUUID()));
        }

        // Not a strict guarantee (two random UUIDs could collide into the
        // same slot), but with a 22.5k-row pool and 50 samples, seeing only
        // one distinct profile back would indicate a broken hash, not luck.
        assertThat(distinctProfiles.size()).isGreaterThan(1);
    }

    @Test
    void lookup_neverReturnsANegativeIndexOutOfBounds() {
        // UUID.hashCode() can be negative - Math.floorMod in BureauPool must
        // still land inside the pool.
        for (int i = 0; i < 1000; i++) {
            assertThat(pool.lookup(UUID.randomUUID())).isNotNull();
        }
    }
}
