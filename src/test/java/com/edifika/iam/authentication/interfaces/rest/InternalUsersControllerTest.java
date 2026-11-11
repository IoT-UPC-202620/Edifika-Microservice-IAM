package com.edifika.iam.authentication.interfaces.rest;

import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalUsersControllerTest {

    private static final String TOKEN = "dev-internal-token";

    @Mock
    private UserRepository userRepository;

    private InternalUsersController newController() {
        return new InternalUsersController(userRepository, TOKEN);
    }

    @Test
    @DisplayName("email existente → 200 {exists: true}, consultado normalizado (lowercase+trim)")
    void emailExistsTrue() {
        when(userRepository.existsByEmail("ana@example.com")).thenReturn(true);
        var response = newController().emailExists("  Ana@Example.com ", TOKEN);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("exists"));
        verify(userRepository).existsByEmail("ana@example.com");
    }

    @Test
    @DisplayName("email inexistente → 200 {exists: false}")
    void emailExistsFalse() {
        when(userRepository.existsByEmail("nuevo@example.com")).thenReturn(false);
        var response = newController().emailExists("nuevo@example.com", TOKEN);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.FALSE, response.getBody().get("exists"));
    }

    @Test
    @DisplayName("X-Internal-Token incorrecto → 401 {message: No autorizado}, sin tocar el repositorio")
    void invalidTokenReturns401() {
        var response = newController().emailExists("ana@example.com", "wrong-token");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("No autorizado", response.getBody().get("message"));
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    @DisplayName("X-Internal-Token ausente → 401 {message: No autorizado}")
    void missingTokenReturns401() {
        var response = newController().emailExists("ana@example.com", null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("No autorizado", response.getBody().get("message"));
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    @DisplayName("email en blanco → 400, sin tocar el repositorio")
    void blankEmailReturns400() {
        var response = newController().emailExists("   ", TOKEN);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(userRepository, never()).existsByEmail(anyString());
    }
}
