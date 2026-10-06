package com.bridgepay.applicant.service;

import com.bridgepay.applicant.domain.Applicant;
import com.bridgepay.applicant.dto.ApplicantResponse;
import com.bridgepay.applicant.dto.InternalApplicantResponse;
import com.bridgepay.applicant.dto.OpsApplicantResponse;
import com.bridgepay.applicant.dto.SignupRequest;
import com.bridgepay.applicant.repository.ApplicantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class ApplicantService {

    private final ApplicantRepository applicantRepository;

    public ApplicantService(ApplicantRepository applicantRepository) {
        this.applicantRepository = applicantRepository;
    }

    @Transactional
    public ApplicantResponse signUp(String keycloakSubjectId, SignupRequest request) {
        if (applicantRepository.existsByKeycloakSubjectId(keycloakSubjectId)) {
            throw new IllegalStateException("An applicant profile already exists for this account");
        }
        if (applicantRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("An applicant with this email already exists");
        }

        Applicant applicant = new Applicant(
                keycloakSubjectId,
                request.firstName(),
                request.lastName(),
                request.dateOfBirth(),
                request.email(),
                request.phone()
        );

        Applicant saved = applicantRepository.save(applicant);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ApplicantResponse getBySubject(String keycloakSubjectId) {
        Applicant applicant = applicantRepository.findByKeycloakSubjectId(keycloakSubjectId)
                .orElseThrow(() -> new NoSuchElementException("No applicant profile found for this account"));
        return toResponse(applicant);
    }

    /**
     * Internal lookups are keyed by the Keycloak subject, not this service's
     * own primary key: the subject is the "applicantId" every other service
     * uses (Application Service stores jwt.getSubject() as it).
     */
    @Transactional(readOnly = true)
    public InternalApplicantResponse getInternalBySubject(String keycloakSubjectId) {
        Applicant applicant = findBySubjectOrThrow(keycloakSubjectId);
        return new InternalApplicantResponse(applicant.getKeycloakSubjectId(), applicant.getFirstName(),
                applicant.getLastName(), applicant.getEmail(), applicant.getPaddleCustomerId());
    }

    @Transactional(readOnly = true)
    public OpsApplicantResponse getForOps(String keycloakSubjectId) {
        return toOpsResponse(findBySubjectOrThrow(keycloakSubjectId));
    }

    @Transactional(readOnly = true)
    public Page<OpsApplicantResponse> search(String q, int page, int size) {
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) {
            throw new IllegalArgumentException("q must be at least 2 characters");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("page must be 0 or more and size between 1 and 50");
        }
        return applicantRepository.search(likePattern(term), PageRequest.of(page, size)).map(this::toOpsResponse);
    }

    /** Lower-cased substring pattern with LIKE's own wildcards (and the escape char) matched literally. */
    static String likePattern(String term) {
        return "%" + term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Transactional
    public void setPaddleCustomerId(String keycloakSubjectId, String paddleCustomerId) {
        findBySubjectOrThrow(keycloakSubjectId).setPaddleCustomerId(paddleCustomerId);
    }

    private Applicant findBySubjectOrThrow(String keycloakSubjectId) {
        return applicantRepository.findByKeycloakSubjectId(keycloakSubjectId)
                .orElseThrow(() -> new NoSuchElementException("No applicant found with id " + keycloakSubjectId));
    }

    private OpsApplicantResponse toOpsResponse(Applicant a) {
        return new OpsApplicantResponse(a.getKeycloakSubjectId(), a.getFirstName(), a.getLastName(), a.getEmail(),
                a.getPhone(), a.getDateOfBirth(), a.getCreatedAt());
    }

    private ApplicantResponse toResponse(Applicant applicant) {
        return new ApplicantResponse(
                applicant.getId(),
                applicant.getFirstName(),
                applicant.getLastName(),
                applicant.getDateOfBirth(),
                applicant.getEmail(),
                applicant.getPhone(),
                applicant.getCreatedAt()
        );
    }
}
