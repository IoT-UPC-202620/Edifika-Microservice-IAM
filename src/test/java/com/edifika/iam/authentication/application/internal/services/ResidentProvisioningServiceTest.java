package com.edifika.iam.authentication.application.internal.services;

import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.mail.CredentialsMailService;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentAssignedToUnitEvent;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
    @DisplayName("crea usuario OWNER con password temporal, activa la cuenta, envía correo y publica el retorno")
    void provisionsNewResident() {
        var ownerRole = new Role(Roles.OWNER);
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.empty());
        var createdUser = new User("Ana Pérez", "ana@example.com", "hash",
                "+51999888777", DocumentType.DNI, "12345678", List.of(ownerRole));
        when(userCommandService.handle(any(com.edifika.iam.authentication.domain.model.commands.SignUpCommand.class))).thenReturn(Optional.of(createdUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.provision(EVENT);

        var commandCaptor = ArgumentCaptor.forClass(com.edifika.iam.authentication.domain.model.commands.SignUpCommand.class);
        verify(userCommandService).handle(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertEquals("ana@example.com", command.email());
        assertEquals("Ana Pérez", command.fullName());
        assertEquals(Roles.OWNER, command.roles().get(0).getName());
        assertTrue(command.password().length() >= 8);

        verify(credentialsMailService).sendCredentials(eq("ana@example.com"), eq("Ana Pérez"), anyString());
        verify(rabbitTemplate).convertAndSend(eq("edifika.events"), eq("iam.resident.user-created"),
                any(com.edifika.iam.authentication.infrastructure.messaging.events.ResidentUserCreatedEvent.class));
    }

    @Test
    @DisplayName("con correo ya registrado no crea usuario ni envía correo, solo re-publica el retorno")
    void duplicateEmailProducesNoUserNoMail() {
        var existing = new User("Ana Pérez", "ana@example.com", "hash",
                "+51999888777", DocumentType.DNI, "12345678", List.of(new Role(Roles.OWNER)));
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(existing));

        service.provision(EVENT);

        verify(userCommandService, never()).handle(any(com.edifika.iam.authentication.domain.model.commands.SignUpCommand.class));
        verify(credentialsMailService, never()).sendCredentials(anyString(), anyString(), anyString());
        verify(rabbitTemplate).convertAndSend(eq("edifika.events"), eq("iam.resident.user-created"),
                any(com.edifika.iam.authentication.infrastructure.messaging.events.ResidentUserCreatedEvent.class));
    }
}
