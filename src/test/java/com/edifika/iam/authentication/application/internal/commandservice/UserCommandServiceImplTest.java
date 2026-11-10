package com.edifika.iam.authentication.application.internal.commandservice;

import com.edifika.iam.authentication.application.internal.outboundservices.hashing.HashingService;
import com.edifika.iam.authentication.application.internal.outboundservices.tokens.TokenService;
import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.domain.model.commands.*;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.RoleRepository;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserCommandServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private HashingService hashingService;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private UserCommandServiceImpl userCommandService;

    private Role adminRole;

    @BeforeEach
    void setUp() {
        adminRole = new Role(Roles.ADMIN);
    }

    @Test
    void handleSignInSuccessfully() {

        var user = mock(User.class);

        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Optional.of(user));

        when(user.getPasswordHash()).thenReturn("hashed");

        when(hashingService.matches("AS-123456", "hashed"))
                .thenReturn(true);

        when(user.getRoles()).thenReturn(Set.of(adminRole));
        when(user.getEmail()).thenReturn("admin@test.com");
        when(user.getId()).thenReturn(1L);

        when(tokenService.generateToken(any(), any(), any()))
                .thenReturn("fake-token");

        var command = new SignInCommand(
                "admin@test.com",
                "AS-123456"
        );

        Optional<ImmutablePair<User, String>> result =
                userCommandService.handle(command);

        assertTrue(result.isPresent());
        assertEquals("fake-token", result.get().getRight());
    }

    @Test
    void handleSignUpSuccessfully() {

        when(userRepository.existsByEmail("admin@test.com"))
                .thenReturn(false);

        when(userRepository.existsByDocumentNumber("12345678"))
                .thenReturn(false);

        when(roleRepository.findByName(Roles.ADMIN))
                .thenReturn(Optional.of(adminRole));

        when(hashingService.encode("N9S8-8777"))
                .thenReturn("hashed-password");

        var savedUser = mock(User.class);

        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Optional.of(savedUser));

        var command = new SignUpCommand(
                "Admin User",
                "admin@test.com",
                "N9S8-8777",
                "999999999",
                DocumentType.DNI,
                "12345678",
                List.of(adminRole)
        );

        Optional<User> result = userCommandService.handle(command);

        assertTrue(result.isPresent());

        verify(userRepository, times(1))
                .save(any(User.class));
    }

    @Test
    void handleSignUpWithDuplicateEmailThrowsAndDoesNotCreateUser() {

        when(userRepository.existsByEmail("admin@test.com"))
                .thenReturn(true);

        var command = new SignUpCommand(
                "Admin User",
                "admin@test.com",
                "N9S8-8777",
                "999999999",
                DocumentType.DNI,
                "12345678",
                List.of(adminRole)
        );

        var exception = assertThrows(IllegalArgumentException.class,
                () -> userCommandService.handle(command));

        assertEquals("El correo ya está registrado", exception.getMessage());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void handleUpdateUserSuccessfully() {

        var user = mock(User.class);

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(user));

        when(hashingService.encode("N9S8--8777"))
                .thenReturn("N9S8-678as");

        when(userRepository.save(user))
                .thenReturn(user);

        var command = new UpdateUserCommand(
                "Nuevo Nombre",
                "nuevo@test.com",
                "N9S8--8777",
                "999999999",
                DocumentType.DNI,
                "74124577",
                "Nuevito@email.com",
                "N9S8-678as"
        );

        var result = userCommandService.handle(command, 1L);

        assertTrue(result.isPresent());

        verify(user).updateFullName("Nuevo Nombre");
        verify(user).updateEmail("nuevo@test.com");
        verify(user).changePassword("N9S8-678as");
    }

    @Test
    void handleDeleteUserSuccessfully() {

        when(userRepository.existsById(1L))
                .thenReturn(true);

        var command = new DeleteUserCommand(1L);

        userCommandService.handle(command);

        verify(userRepository, times(1))
                .deleteById(1L);
    }
}