package com.edifika.iam.authentication.interfaces.rest.resources;

import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;

/**
 * Alta de residente por un ADMIN. role: OWNER o TENANT. password opcional (si falta, se genera).
 */
public record CreateResidentUserResource(
        String fullName,
        String email,
        String phone,
        DocumentType documentType,
        String documentNumber,
        String role,
        String password
) {}
