package com.edifika.iam.authentication.infrastructure.messaging.events;

/**
 * Publica en edifika.events (routing key iam.resident.user-created) tras crear
 * o detectar el usuario OWNER. Completa el vínculo PENDIENTE en Residential.
 */
public record ResidentUserCreatedEvent(
        String messageId,
        String email,
        Long unitId,
        Long idUser
) {
}
