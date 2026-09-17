package com.bridgepay.applicant.service;

import com.bridgepay.applicant.domain.Applicant;
import com.bridgepay.applicant.dto.ApplicantResponse;
import com.bridgepay.applicant.dto.InternalApplicantResponse;
import com.bridgepay.applicant.dto.SignupRequest;
import com.bridgepay.applicant.repository.ApplicantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public InternalApplicantResponse getById(UUID id) {
        Applicant applicant = applicantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No applicant found with id " + id));
        return new InternalApplicantResponse(applicant.getId(), applicant.getFirstName(), applicant.getLastName(),
                applicant.getEmail(), applicant.getPaddleCustomerId());
    }

    @Transactional
    public void setPaddleCustomerId(UUID id, String paddleCustomerId) {
        Applicant applicant = applicantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No applicant found with id " + id));
        applicant.setPaddleCustomerId(paddleCustomerId);
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
