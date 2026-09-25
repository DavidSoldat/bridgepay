package com.bridgepay.applicant.service;

import com.bridgepay.applicant.domain.Applicant;
import com.bridgepay.applicant.dto.ApplicantResponse;
import com.bridgepay.applicant.dto.SignupRequest;
import com.bridgepay.applicant.repository.ApplicantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicantServiceTest {

    @Mock
    private ApplicantRepository applicantRepository;

    private ApplicantService applicantService;

    private SignupRequest validRequest() {
        return new SignupRequest("Ana", "Doe", LocalDate.of(1995, 4, 12), "ana@example.com", "+38765123456");
    }

    @Test
    void signUp_createsApplicant_whenSubjectAndEmailAreNew() {
        applicantService = new ApplicantService(applicantRepository);
        when(applicantRepository.existsByKeycloakSubjectId("kc-123")).thenReturn(false);
        when(applicantRepository.existsByEmail("ana@example.com")).thenReturn(false);
        when(applicantRepository.save(any(Applicant.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicantResponse response = applicantService.signUp("kc-123", validRequest());

        assertThat(response.firstName()).isEqualTo("Ana");
        assertThat(response.email()).isEqualTo("ana@example.com");
        verify(applicantRepository).save(any(Applicant.class));
    }

    @Test
    void signUp_throws_whenSubjectAlreadyHasAProfile() {
        applicantService = new ApplicantService(applicantRepository);
        when(applicantRepository.existsByKeycloakSubjectId("kc-123")).thenReturn(true);

        assertThatThrownBy(() -> applicantService.signUp("kc-123", validRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        verify(applicantRepository, never()).save(any());
    }

    @Test
    void signUp_throws_whenEmailAlreadyUsed() {
        applicantService = new ApplicantService(applicantRepository);
        when(applicantRepository.existsByKeycloakSubjectId("kc-123")).thenReturn(false);
        when(applicantRepository.existsByEmail("ana@example.com")).thenReturn(true);

        assertThatThrownBy(() -> applicantService.signUp("kc-123", validRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email already exists");

        verify(applicantRepository, never()).save(any());
    }

    @Test
    void getBySubject_throws_whenNoProfileExists() {
        applicantService = new ApplicantService(applicantRepository);
        when(applicantRepository.findByKeycloakSubjectId("kc-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicantService.getBySubject("kc-999"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getInternalBySubject_returnsApplicantKeyedByItsSubject_whenFound() {
        applicantService = new ApplicantService(applicantRepository);
        Applicant applicant = new Applicant("kc-123", "Ana", "Doe",
                LocalDate.of(1995, 4, 12), "ana@example.com", "+38765123456");
        when(applicantRepository.findByKeycloakSubjectId("kc-123")).thenReturn(Optional.of(applicant));

        var response = applicantService.getInternalBySubject("kc-123");

        assertThat(response.id()).isEqualTo("kc-123");
        assertThat(response.email()).isEqualTo("ana@example.com");
        assertThat(response.paddleCustomerId()).isNull();
    }

    @Test
    void getInternalBySubject_throws_whenNotFound() {
        applicantService = new ApplicantService(applicantRepository);
        when(applicantRepository.findByKeycloakSubjectId("kc-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicantService.getInternalBySubject("kc-missing"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void setPaddleCustomerId_updatesTheManagedEntity_lookedUpBySubject() {
        applicantService = new ApplicantService(applicantRepository);
        Applicant applicant = new Applicant("kc-123", "Ana", "Doe",
                LocalDate.of(1995, 4, 12), "ana@example.com", "+38765123456");
        when(applicantRepository.findByKeycloakSubjectId("kc-123")).thenReturn(Optional.of(applicant));

        applicantService.setPaddleCustomerId("kc-123", "ctm_01abc");

        assertThat(applicant.getPaddleCustomerId()).isEqualTo("ctm_01abc");
    }
}
