package com.bridgepay.applicant.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "applicants", schema = "applicant")
@EntityListeners(AuditingEntityListener.class)
public class Applicant {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "keycloak_subject_id", nullable = false, unique = true, length = 64)
    private String keycloakSubjectId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "paddle_customer_id", length = 64)
    private String paddleCustomerId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Applicant() {}

    public Applicant(String keycloakSubjectId, String firstName, String lastName,
                     LocalDate dateOfBirth, String email, String phone) {
        this.id = UuidCreator.getTimeOrderedEpoch();
        this.keycloakSubjectId = keycloakSubjectId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.email = email;
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakSubjectId() {
        return keycloakSubjectId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPaddleCustomerId() {
        return paddleCustomerId;
    }

    public void setPaddleCustomerId(String paddleCustomerId) {
        this.paddleCustomerId = paddleCustomerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
