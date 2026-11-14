package com.edifika.iam.authentication.interfaces.rest.resources;

import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;

/** Resultado de GET /api/v1/users/search: solo OWNER/TENANT. */
public record ResidentSearchResource(
        Long id,
        String fullName,
        String email,
        String phone,
        DocumentType documentType,
        String documentNumber,
        String role
) {}
