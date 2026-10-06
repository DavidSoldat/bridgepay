package com.bridgepay.applicant.repository;

import com.bridgepay.applicant.domain.Applicant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApplicantRepository extends JpaRepository<Applicant, UUID> {

    Optional<Applicant> findByKeycloakSubjectId(String keycloakSubjectId);

    boolean existsByKeycloakSubjectId(String keycloakSubjectId);

    boolean existsByEmail(String email);

    // ponytail: sequential scan; add a pg_trgm GIN index when the table grows
    @Query("""
            select a from Applicant a
            where lower(a.email) like :pattern escape '\\'
               or lower(concat(a.firstName, ' ', a.lastName)) like :pattern escape '\\'
            order by a.createdAt desc, a.id desc""")
    Page<Applicant> search(@Param("pattern") String pattern, Pageable pageable);
}
