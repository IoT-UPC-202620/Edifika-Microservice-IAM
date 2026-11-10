package com.edifika.iam.authentication.application.internal.services;

import com.edifika.iam.authentication.domain.model.commands.SignUpCommand;
import com.edifika.iam.authentication.domain.model.entities.Role;
import com.edifika.iam.authentication.domain.model.valueobjects.DocumentType;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.mail.CredentialsMailService;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentAssignedToUnitEvent;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentUserCreatedEvent;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Provisiona el usuario OWNER de un residente vinculado a una unidad.
 * Idempotente por email: si el usuario ya existe, se re-publica el evento de retorno
 * (el link en Residential pudo no completarse tras un reintento) sin recrear ni re-enviar correo.
 */
@Service
public class ResidentProvisioningService {

    public static final String USER_CREATED_EXCHANGE = "edifika.events";
    public static final String USER_CREATED_ROUTING_KEY = "iam.resident.user-created";

    private static final Logger log = LoggerFactory.getLogger(ResidentProvisioningService.class);
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserCommandService userCommandService;
    private final UserRepository userRepository;
    private final CredentialsMailService credentialsMailService;
    private final RabbitTemplate rabbitTemplate;
    private final SecureRandom random = new SecureRandom();

    public ResidentProvisioningService(UserCommandService userCommandService,
                                       UserRepository userRepository,
                                       CredentialsMailService credentialsMailService,
                                       RabbitTemplate rabbitTemplate) {
        this.userCommandService = userCommandService;
        this.userRepository = userRepository;
        this.credentialsMailService = credentialsMailService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public void provision(ResidentAssignedToUnitEvent event) {
        var email = event.email().trim().toLowerCase();

        var existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            log.warn("El correo ya está registrado ({}). No se crea usuario; se re-publica el evento de retorno.", email);
            publishUserCreatedAfterCommit(email, event.unitId(), existing.get().getId());
            return;
        }

        var temporaryPassword = generateTemporaryPassword(event.fullName(), email);
        var documentType = parseDocumentType(event.documentType());

        var user = userCommandService.handle(new SignUpCommand(
                event.fullName(),
                email,
                temporaryPassword,
                event.phone(),
                documentType,
                event.documentNumber(),
                List.of(new Role(Roles.OWNER))
        )).orElseThrow(() -> new IllegalStateException("No se pudo crear el usuario residente"));

        user.activate();
        userRepository.save(user);
        log.info("Usuario OWNER creado para residente {} (id={})", email, user.getId());

        credentialsMailService.sendCredentials(email, event.fullName(), temporaryPassword);

        publishUserCreatedAfterCommit(email, event.unitId(), user.getId());
    }

    /** Publica tras el commit para que Residential nunca vea un usuario inexistente o ausente. */
    private void publishUserCreatedAfterCommit(String email, Long unitId, Long idUser) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishUserCreated(email, unitId, idUser);
            return;
        }
        var userCreated = new ResidentUserCreatedEvent(UUID.randomUUID().toString(), email, unitId, idUser);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishUserCreated(userCreated);
            }
        });
    }

    private void publishUserCreated(String email, Long unitId, Long idUser) {
        publishUserCreated(new ResidentUserCreatedEvent(UUID.randomUUID().toString(), email, unitId, idUser));
    }

    private void publishUserCreated(ResidentUserCreatedEvent userCreated) {
        rabbitTemplate.convertAndSend(USER_CREATED_EXCHANGE, USER_CREATED_ROUTING_KEY, userCreated);
        log.info("Publicado {} (email={}, unitId={}, idUser={})", USER_CREATED_ROUTING_KEY,
                userCreated.email(), userCreated.unitId(), userCreated.idUser());
    }

    private DocumentType parseDocumentType(String value) {
        if (value == null || value.isBlank()) {
            return DocumentType.DNI;
        }
        try {
            return DocumentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("documentType desconocido '{}'; se usa DNI", value);
            return DocumentType.DNI;
        }
    }

    private String generateTemporaryPassword(String fullName, String email) {
        // Política SignUpCommand: >=8, una mayúscula, un símbolo, sin nombre ni parte del email.
        var firstName = fullName == null ? "" : fullName.trim().split(" ")[0].toLowerCase();
        var emailLocal = email.split("@")[0].toLowerCase();
        for (int attempt = 0; attempt < 20; attempt++) {
            var sb = new StringBuilder();
            sb.append("ABCDEFGHJKLMNPQRSTUVWXYZ".charAt(random.nextInt(24))); // asegura mayúscula
            for (int i = 0; i < 8; i++) {
                sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
            }
            sb.append("!").append(random.nextInt(10)); // asegura símbolo y dígito
            var candidate = sb.toString();
            var lower = candidate.toLowerCase();
            if (!lower.contains(firstName) && !lower.contains(emailLocal)) {
                return candidate;
            }
        }
        return "Edifika" + UUID.randomUUID().toString().replace("-", "").substring(0, 6) + "!7";
    }
}
