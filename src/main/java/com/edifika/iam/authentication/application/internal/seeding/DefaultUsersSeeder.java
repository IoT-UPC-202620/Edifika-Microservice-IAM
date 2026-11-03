package com.edifika.iam.authentication.application.internal.seeding;

import com.edifika.iam.authentication.domain.model.commands.SignUpCommand;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Crea los dos usuarios por defecto al arrancar: un administrador (gestiona los
 * lectores y registra tarjetas desde la app) y un propietario (a quien se le
 * asocian tarjetas). Es idempotente: si el email ya existe no hace nada.
 *
 * <p>Las credenciales se pueden cambiar por variables de entorno y el seed se
 * desactiva con {@code SEED_DEFAULT_USERS=false}.</p>
 */
@Service
public class DefaultUsersSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultUsersSeeder.class);

    /** Datos de un usuario por defecto. */
    public record DefaultUser(String fullName, String email, String password, String phone,
                              String documentNumber, Roles role) {
    }

    private final UserCommandService userCommandService;
    private final UserRepository userRepository;
    private final boolean enabled;
    private final List<DefaultUser> users;

    @Autowired
    public DefaultUsersSeeder(
            UserCommandService userCommandService,
            UserRepository userRepository,
            @Value("${edifika.seed.default-users.enabled:true}") boolean enabled,
            @Value("${edifika.seed.admin.email:admin@edifika.com}") String adminEmail,
            @Value("${edifika.seed.admin.password:Edifika#2026}") String adminPassword,
            @Value("${edifika.seed.owner.email:propietario@edifika.com}") String ownerEmail,
            @Value("${edifika.seed.owner.password:Edifika#2026}") String ownerPassword) {
        this(userCommandService, userRepository, enabled, List.of(
                new DefaultUser("Administrador Edifika", adminEmail, adminPassword,
                        "900000001", "10000001", Roles.ADMIN),
                new DefaultUser("Propietario Demo", ownerEmail, ownerPassword,
                        "900000002", "10000002", Roles.OWNER)
        ));
    }

    DefaultUsersSeeder(UserCommandService userCommandService, UserRepository userRepository,
                       boolean enabled, List<DefaultUser> users) {
        this.userCommandService = userCommandService;
        this.userRepository = userRepository;
        this.enabled = enabled;
        this.users = users;
    }

    /**
     * Crea los usuarios que falten. Requiere que los roles ya estén sembrados.
     * Corre en una transacción: al arrancar no hay sesión abierta (open-in-view solo
     * cubre peticiones web) y los roles leídos quedarían detached al guardar el usuario.
     */
    @Transactional
    public void seed() {
        if (!enabled) {
            LOGGER.info("Seed de usuarios por defecto desactivado");
            return;
        }
        for (DefaultUser user : users) {
            seedOne(user);
        }
    }

    private void seedOne(DefaultUser user) {
        if (userRepository.existsByEmail(user.email())) {
            LOGGER.info("Usuario por defecto {} ya existe", user.email());
            return;
        }
        if (userRepository.existsByDocumentNumber(user.documentNumber())) {
            LOGGER.warn("No se crea {}: el documento {} ya pertenece a otro usuario", user.email(), user.documentNumber());
            return;
        }
        try {
            userCommandService.handle(new SignUpCommand(
                    user.fullName(),
                    user.email(),
                    user.password(),
                    user.phone(),
                    DocumentType.DNI,
                    user.documentNumber(),
                    List.of(new Role(user.role()))
            ));
            LOGGER.info("Usuario por defecto creado: {} ({})", user.email(), user.role());
        } catch (IllegalArgumentException e) {
            // Contraseña configurada que no cumple la política, por ejemplo: no se detiene el arranque.
            LOGGER.error("No se pudo crear el usuario por defecto {}: {}", user.email(), e.getMessage());
        }
    }
}
