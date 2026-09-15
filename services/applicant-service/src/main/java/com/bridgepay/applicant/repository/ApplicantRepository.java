package com.bridgepay.applicant.repository;

import com.bridgepay.applicant.domain.Applicant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApplicantRepository extends JpaRepository<Applicant, UUID> {

    Optional<Applicant> findByKeycloakSubjectId(String keycloakSubjectId);

    boolean existsByKeycloakSubjectId(String keycloakSubjectId);

    boolean existsByEmail(String email);
}
