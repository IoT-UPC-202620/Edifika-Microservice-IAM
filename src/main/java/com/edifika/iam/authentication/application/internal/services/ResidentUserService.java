package com.edifika.iam.authentication.application.internal.services;

import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.domain.model.commands.SignUpCommand;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.mail.CredentialsMailService;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Alta y busqueda de residentes (OWNER / TENANT) por un ADMIN. Nunca crea ni devuelve ADMIN.
 */
@Service
public class ResidentUserService {

    public static final Set<Roles> RESIDENT_ROLES = Set.of(Roles.OWNER, Roles.TENANT);
    private static final Logger log = LoggerFactory.getLogger(ResidentUserService.class);

    private final UserCommandService userCommandService;
    private final UserRepository userRepository;
    private final CredentialsMailService credentialsMailService;
    private final TemporaryPasswordGenerator passwordGenerator;

    public ResidentUserService(UserCommandService userCommandService,
                               UserRepository userRepository,
                               CredentialsMailService credentialsMailService,
                               TemporaryPasswordGenerator passwordGenerator) {
        this.userCommandService = userCommandService;
        this.userRepository = userRepository;
        this.credentialsMailService = credentialsMailService;
        this.passwordGenerator = passwordGenerator;
    }

    /** OWNER o TENANT; cualquier otro valor (ADMIN incluido) es IllegalArgumentException -> 400. */
    public static Roles parseResidentRole(String role) {
        if (role != null) {
            try {
                var parsed = Roles.valueOf(role.trim().toUpperCase());
                if (RESIDENT_ROLES.contains(parsed)) return parsed;
            } catch (IllegalArgumentException ignored) {
                // cae al error de abajo
            }
        }
        throw new IllegalArgumentException("El rol debe ser OWNER o TENANT");
    }

    /**
     * Crea el usuario activo y envia sus credenciales por correo (best effort, como Register Resident).
     * Si no se envia password, se genera una temporal.
     */
    public User create(String fullName, String email, String phone, DocumentType documentType,
                       String documentNumber, String role, String password) {
        var residentRole = parseResidentRole(role);
        var normalizedEmail = email == null ? null : email.trim().toLowerCase();
        var finalPassword = (password == null || password.isBlank())
                ? passwordGenerator.generate(fullName, normalizedEmail == null ? "" : normalizedEmail)
                : password;
        var user = userCommandService.handle(new SignUpCommand(
                fullName, normalizedEmail, finalPassword, phone,
                documentType == null ? DocumentType.DNI : documentType, documentNumber,
                List.of(new Role(residentRole))
        )).orElseThrow(() -> new IllegalStateException("No se pudo crear el residente"));
        user.activate();
        userRepository.save(user);
        try {
            credentialsMailService.sendCredentials(user.getEmail(), user.getFullName(), finalPassword);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de credenciales a {}: {}", user.getEmail(), e.getMessage());
        }
        log.info("Residente {} creado (id={})", residentRole, user.getId());
        return user;
    }

    /** Busca solo OWNER/TENANT por documento (prefijo) o por nombre (contiene). Minimo 3 caracteres. */
    public List<User> search(String documentNumber, String name) {
        Collection<Roles> roles = RESIDENT_ROLES;
        if (documentNumber != null && documentNumber.trim().length() >= 3) {
            return userRepository.findDistinctTop20ByDocumentNumberStartingWithAndRoles_NameIn(documentNumber.trim(), roles);
        }
        if (name != null && name.trim().length() >= 3) {
            return userRepository.findDistinctTop20ByFullNameContainingIgnoreCaseAndRoles_NameIn(name.trim(), roles);
        }
        throw new IllegalArgumentException("Envia documentNumber o name con al menos 3 caracteres");
    }
}
