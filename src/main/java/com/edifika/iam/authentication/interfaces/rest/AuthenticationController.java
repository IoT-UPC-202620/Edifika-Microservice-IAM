package com.edifika.iam.authentication.interfaces.rest;

import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.RoleRepository;
import com.edifika.iam.authentication.interfaces.rest.resources.AuthenticatedUserResource;
import com.edifika.iam.authentication.interfaces.rest.resources.SignInResource;
import com.edifika.iam.authentication.interfaces.rest.resources.SignUpResource;
import com.edifika.iam.authentication.interfaces.rest.resources.UserResource;
import com.edifika.iam.authentication.interfaces.rest.transform.AuthenticatedUserResourceFromEntityAssembler;
import com.edifika.iam.authentication.interfaces.rest.transform.SignInCommandFromResourceAssembler;
import com.edifika.iam.authentication.interfaces.rest.transform.SignUpCommandFromResourceAssembler;
import com.edifika.iam.authentication.interfaces.rest.transform.UserResourceFromEntityAssembler;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para autenticación de usuarios.
 * Maneja el registro e inicio de sesión en Edifika.
 */
@RestController
@RequestMapping(value = "/api/v1/authentication", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Endpoints de autenticación")
public class AuthenticationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationController.class);
    private final UserCommandService userCommandService;
    private final RoleRepository roleRepository;

    public AuthenticationController(UserCommandService userCommandService,
                                    RoleRepository roleRepository) {
        this.userCommandService = userCommandService;
        this.roleRepository = roleRepository;
    }

    @PostMapping("/sign-up")
    public ResponseEntity<?> signUp(@RequestBody SignUpResource resource) {
        try {
            // Registro publico web: el usuario creado siempre es ADMIN; el rol del request se ignora.
            // Los residentes (OWNER/TENANT) los crea un ADMIN con POST /api/v1/users/residents.
            var adminOnly = new SignUpResource(resource.fullName(), resource.email(), resource.password(),
                    resource.phone(), resource.documentType(), resource.documentNumber(),
                    List.of(Roles.ADMIN.name()));
            var command = SignUpCommandFromResourceAssembler
                    .toCommandFromResource(adminOnly, roleRepository);
            var user = userCommandService.handle(command);
            if (user.isEmpty()) {
                LOGGER.warn("Sign up fallido para el email: {}", resource.email());
                return ResponseEntity.badRequest().body("No se pudo registrar el usuario");
            }
            LOGGER.info("Usuario registrado exitosamente: {}", resource.email());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(UserResourceFromEntityAssembler.toResourceFromEntity(user.get()));
        } catch (IllegalArgumentException e) {
            LOGGER.error("Validacion fallida en sign up: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error en sign up para el email {}: {}", resource.email(), e.getMessage());
            return ResponseEntity.badRequest().body("Error al registrar el usuario");
        }
    }

    @PostMapping("/sign-in")
    public ResponseEntity<?> signIn(@RequestBody SignInResource resource) {
        try {
            var command = SignInCommandFromResourceAssembler.toCommandFromResource(resource);
            var result = userCommandService.handle(command);
            if (result.isEmpty()) {
                LOGGER.warn("Sign in fallido para el email: {}", resource.email());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Credenciales incorrectas");
            }
            var user = result.get().getLeft();
            var token = result.get().getRight();
            LOGGER.info("Sign in exitoso para el email: {}", resource.email());
            return ResponseEntity.ok(
                    AuthenticatedUserResourceFromEntityAssembler.toResourceFromEntity(user, token));
        } catch (IllegalArgumentException e) {
            LOGGER.error("Validacion fallida en sign in: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error en sign in para el email {}: {}", resource.email(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Credenciales incorrectas");
        }
    }
}