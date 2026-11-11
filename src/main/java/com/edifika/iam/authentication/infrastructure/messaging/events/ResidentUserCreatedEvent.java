package com.edifika.iam.authentication.infrastructure.messaging.events;

/**
 * Publica en edifika.events (routing key iam.resident.user-created) tras crear
 * el usuario OWNER o detectar que el correo ya estaba registrado.
 * Completa el vínculo PENDIENTE en Residential.
 *
 * <p>{@code idUser} es nullable: solo es null cuando {@code status} es REJECTED.
 * {@code status} es CREATED o REJECTED; REJECTED lleva {@code reason=EMAIL_ALREADY_REGISTERED}.
 */
public record ResidentUserCreatedEvent(
        String email,
        Long unitId,
        Long idUser,
        String messageId,
        String status,
        String reason
) {
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String REASON_EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";

    public static ResidentUserCreatedEvent created(String email, Long unitId, Long idUser, String messageId) {
        return new ResidentUserCreatedEvent(email, unitId, idUser, messageId, STATUS_CREATED, null);
    }

    public static ResidentUserCreatedEvent rejectedDuplicateEmail(String email, Long unitId, String messageId) {
        return new ResidentUserCreatedEvent(email, unitId, null, messageId, STATUS_REJECTED, REASON_EMAIL_ALREADY_REGISTERED);
    }
}
