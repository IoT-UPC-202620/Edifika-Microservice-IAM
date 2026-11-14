package com.edifika.iam.authentication.interfaces.rest;

import com.edifika.iam.authentication.application.internal.services.ResidentUserService;
import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.interfaces.rest.resources.CreateResidentUserResource;
import com.edifika.iam.authentication.interfaces.rest.resources.ResidentSearchResource;
import com.edifika.iam.authentication.interfaces.rest.transform.UserResourceFromEntityAssembler;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Gestion de residentes (OWNER / TENANT) por un ADMIN.
 * POST /api/v1/users/residents   alta + correo con credenciales
 * GET  /api/v1/users/search      busqueda por documento o nombre (nunca devuelve ADMIN)
 */
@RestController
@RequestMapping(value = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Residents", description = "Alta y busqueda de residentes (solo ADMIN)")
@PreAuthorize("hasAuthority('ADMIN')")
public class ResidentUsersController {

    private final ResidentUserService residentUserService;

    public ResidentUsersController(ResidentUserService residentUserService) {
        this.residentUserService = residentUserService;
    }

    @PostMapping("/residents")
    public ResponseEntity<?> createResident(@RequestBody CreateResidentUserResource resource) {
        try {
            var user = residentUserService.create(resource.fullName(), resource.email(), resource.phone(),
                    resource.documentType(), resource.documentNumber(), resource.role(), resource.password());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(UserResourceFromEntityAssembler.toResourceFromEntity(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String documentNumber,
                                    @RequestParam(required = false) String name) {
        try {
            List<ResidentSearchResource> result = residentUserService.search(documentNumber, name).stream()
                    .map(ResidentUsersController::toSearchResource)
                    .toList();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private static ResidentSearchResource toSearchResource(User user) {
        var role = user.getRoles().stream()
                .map(r -> r.getName())
                .filter(ResidentUserService.RESIDENT_ROLES::contains)
                .map(Enum::name)
                .findFirst().orElse(null);
        return new ResidentSearchResource(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getDocumentType(), user.getDocumentNumber(), role);
    }
}
