package com.edifika.iam.authentication.interfaces.rest;

import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Endpoints internos servicio-a-servicio (no son para usuarios finales).
 *
 * <p>No usan JWT de usuario: se protegen con el header {@code X-Internal-Token},
 * cuyo valor viaja en la propiedad {@code app.internal-api-token} (env {@code APP_INTERNAL_TOKEN},
 * default dev {@code dev-internal-token}). Bajo {@code /api/v1/authentication/**} la cadena
 * de seguridad es permitAll, así que este header es la única barrera: no confiar en la red.
 *
 * Ver {@code docs/adr/0001-vinculo-por-email-id-user-diferido.md} (dependencia síncrona
 * Residential→IAM por el pre-chequeo E3, fail closed en el cliente).
 */
@RestController
@Hidden
@RequestMapping(value = "/api/v1/authentication/internal/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalUsersController {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final UserRepository userRepository;
    private final String internalToken;

    public InternalUsersController(UserRepository userRepository,
                                   @Value("${app.internal-api-token:dev-internal-token}") String internalToken) {
        this.userRepository = userRepository;
        this.internalToken = internalToken;
    }

    /**
     * Pre-chequeo E3: ¿existe ya un usuario con este correo?
     * Normaliza lowercase+trim antes de consultar (mismo criterio que el sign-up).
     */
    @GetMapping("/email-exists")
    public ResponseEntity<Map<String, Object>> emailExists(
            @org.springframework.web.bind.annotation.RequestParam("email") String email,
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "No autorizado"));
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "El parámetro email es requerido"));
        }
        var normalized = email.trim().toLowerCase();
        return ResponseEntity.ok(Map.of("exists", userRepository.existsByEmail(normalized)));
    }

    private boolean tokenMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        // Comparación en tiempo constante para no filtrar el token por timing.
        var expected = internalToken.getBytes(StandardCharsets.UTF_8);
        var actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
