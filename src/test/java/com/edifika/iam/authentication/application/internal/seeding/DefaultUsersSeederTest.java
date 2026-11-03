package com.edifika.iam.authentication.application.internal.seeding;

import com.edifika.iam.authentication.domain.model.commands.SignUpCommand;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultUsersSeederTest {

    @Mock
    private UserCommandService userCommandService;

    @Mock
    private UserRepository userRepository;

    private DefaultUsersSeeder seederWithDefaults(boolean enabled) {
        return new DefaultUsersSeeder(userCommandService, userRepository, enabled,
                "admin@edifika.com", "Edifika#2026", "propietario@edifika.com", "Edifika#2026");
    }

    @Test
    void createsAnAdminAndAnOwnerWhenTheyDoNotExist() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByDocumentNumber(anyString())).thenReturn(false);

        seederWithDefaults(true).seed();

        ArgumentCaptor<SignUpCommand> captor = ArgumentCaptor.forClass(SignUpCommand.class);
        verify(userCommandService, times(2)).handle(captor.capture());
        List<SignUpCommand> commands = captor.getAllValues();
        assertThat(commands).extracting(SignUpCommand::email)
                .containsExactly("admin@edifika.com", "propietario@edifika.com");
        assertThat(commands.get(0).roles()).extracting(r -> r.getName()).containsExactly(Roles.ADMIN);
        assertThat(commands.get(1).roles()).extracting(r -> r.getName()).containsExactly(Roles.OWNER);
    }

    @Test
    void doesNothingForUsersThatAlreadyExist() {
        when(userRepository.existsByEmail("admin@edifika.com")).thenReturn(true);
        when(userRepository.existsByEmail("propietario@edifika.com")).thenReturn(false);
        when(userRepository.existsByDocumentNumber(anyString())).thenReturn(false);

        seederWithDefaults(true).seed();

        ArgumentCaptor<SignUpCommand> captor = ArgumentCaptor.forClass(SignUpCommand.class);
        verify(userCommandService, times(1)).handle(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("propietario@edifika.com");
    }

    @Test
    void skipsAUserWhoseDocumentNumberIsTaken() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByDocumentNumber(anyString())).thenReturn(true);

        seederWithDefaults(true).seed();

        verify(userCommandService, never()).handle(any(SignUpCommand.class));
    }

    @Test
    void canBeDisabled() {
        seederWithDefaults(false).seed();

        verifyNoInteractions(userRepository, userCommandService);
    }

    @Test
    void aPasswordThatBreaksThePolicyIsLoggedWithoutStoppingStartup() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByDocumentNumber(anyString())).thenReturn(false);
        var seeder = new DefaultUsersSeeder(userCommandService, userRepository, true,
                "admin@edifika.com", "corta", "propietario@edifika.com", "Edifika#2026");

        assertThatCode(seeder::seed).doesNotThrowAnyException();

        // El administrador falla en la validación del comando; el propietario igual se crea
        verify(userCommandService, times(1)).handle(any(SignUpCommand.class));
    }
}
