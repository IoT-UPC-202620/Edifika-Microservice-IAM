package com.edifika.iam.authentication.application.internal.services;

import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.domain.model.commands.SignUpCommand;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.mail.CredentialsMailService;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentAssignedToUnitEvent;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentUserCreatedEvent;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResidentProvisioningServiceTest {

    @Mock
    private UserCommandService userCommandService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CredentialsMailService credentialsMailService;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private ResidentProvisioningService service;

    private static final ResidentAssignedToUnitEvent EVENT = new ResidentAssignedToUnitEvent(
            "msg-1", "Ana Pérez", "Ana@Example.com", "999888777", "DNI", "12345678",
            1L, 7L, 501, 5, "2026-11-10T13:00:00");

    @BeforeEach
    void setUp() {
        service = new ResidentProvisioningService(userCommandService, userRepository,
                credentialsMailService, rabbitTemplate);
    }

    @Test
    @DisplayName("crea usuario OWNER con password temporal, activa la cuenta, envía correo y publica el retorno CREATED")
    void provisionsNewResident() {
        var ownerRole = new Role(Roles.OWNER);
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.empty());
        var createdUser = new User("Ana Pérez", "ana@example.com", "hash",
                "+51999888777", DocumentType.DNI, "12345678", List.of(ownerRole));
        ReflectionTestUtils.setField(createdUser, "id", 42L);
        when(userCommandService.handle(any(SignUpCommand.class))).thenReturn(Optional.of(createdUser));

        service.provision(EVENT);

        var commandCaptor = ArgumentCaptor.forClass(SignUpCommand.class);
        verify(userCommandService).handle(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertEquals("ana@example.com", command.email());
        assertEquals("Ana Pérez", command.fullName());
        assertEquals(Roles.OWNER, command.roles().get(0).getName());
        assertTrue(command.password().length() >= 8);
        assertEquals("ACTIVE", createdUser.getStatus());

        // handle(SignUpCommand) ya persiste el User; provision no debe volver a guardar.
        verify(userRepository, never()).save(any(User.class));

        verify(credentialsMailService).sendCredentials(eq("ana@example.com"), eq("Ana Pérez"), anyString());

        var eventCaptor = ArgumentCaptor.forClass(ResidentUserCreatedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("edifika.events"), eq("iam.resident.user-created"),
                eventCaptor.capture());
        var published = eventCaptor.getValue();
        assertEquals("ana@example.com", published.email());
        assertEquals(7L, published.unitId());
        assertEquals(42L, published.idUser());
        assertEquals(ResidentUserCreatedEvent.STATUS_CREATED, published.status());
        assertNull(published.reason());
        assertNotNull(published.messageId());
    }

    @Test
    @DisplayName("carrera pre-chequeo/creación: handle falla pero el usuario aparece → REJECTED, sin correo ni excepción")
    void signUpRacePublishesRejected() {
        var existing = new User("Ana Pérez", "ana@example.com", "hash",
                "+51999888777", DocumentType.DNI, "12345678", List.of(new Role(Roles.OWNER)));
        ReflectionTestUtils.setField(existing, "id", 99L);
        when(userRepository.findByEmail("ana@example.com"))
                .thenReturn(Optional.empty())   // pre-chequeo: aún no existía
                .thenReturn(Optional.of(existing)); // re-chequeo tras la excepción: sí existe
        when(userCommandService.handle(any(SignUpCommand.class)))
                .thenThrow(new IllegalArgumentException("El correo ya está registrado"));

        service.provision(EVENT);

        verify(userCommandService).handle(any(SignUpCommand.class));
        verify(credentialsMailService, never()).sendCredentials(anyString(), anyString(), anyString());

        var eventCaptor = ArgumentCaptor.forClass(ResidentUserCreatedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("edifika.events"), eq("iam.resident.user-created"),
                eventCaptor.capture());
        var published = eventCaptor.getValue();
        assertEquals(ResidentUserCreatedEvent.STATUS_REJECTED, published.status());
        assertEquals(ResidentUserCreatedEvent.REASON_EMAIL_ALREADY_REGISTERED, published.reason());
        assertNull(published.idUser());
    }

    @Test
    @DisplayName("fallo de sign-up que no es duplicado de email: se relanza para retry → DLQ, sin evento")
    void signUpOtherFailureRethrows() {
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.empty());
        when(userCommandService.handle(any(SignUpCommand.class)))
                .thenThrow(new IllegalArgumentException("Rol no encontrado: OWNER"));

        assertThrows(IllegalArgumentException.class, () -> service.provision(EVENT));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(credentialsMailService, never()).sendCredentials(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("con correo ya registrado no crea usuario ni envía correo; publica el retorno REJECTED")
    void duplicateEmailProducesNoUserNoMail() {
        var existing = new User("Ana Pérez", "ana@example.com", "hash",
                "+51999888777", DocumentType.DNI, "12345678", List.of(new Role(Roles.OWNER)));
        ReflectionTestUtils.setField(existing, "id", 99L);
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(existing));

        service.provision(EVENT);

        verify(userCommandService, never()).handle(any(SignUpCommand.class));
        verify(userRepository, never()).save(any(User.class));
        verify(credentialsMailService, never()).sendCredentials(anyString(), anyString(), anyString());

        var eventCaptor = ArgumentCaptor.forClass(ResidentUserCreatedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("edifika.events"), eq("iam.resident.user-created"),
                eventCaptor.capture());
        var published = eventCaptor.getValue();
        assertEquals("ana@example.com", published.email());
        assertEquals(7L, published.unitId());
        assertNull(published.idUser());
        assertEquals(ResidentUserCreatedEvent.STATUS_REJECTED, published.status());
        assertEquals(ResidentUserCreatedEvent.REASON_EMAIL_ALREADY_REGISTERED, published.reason());
        assertNotNull(published.messageId());
    }
}
