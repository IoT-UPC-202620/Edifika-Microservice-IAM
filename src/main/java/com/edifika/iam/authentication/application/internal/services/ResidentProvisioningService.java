package com.edifika.iam.authentication.application.internal.services;

import com.edifika.iam.authentication.domain.model.aggregates.User;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Provisiona el usuario OWNER de un residente vinculado a una unidad.
 * Idempotente por email: si el usuario ya existe, se publica el retorno REJECTED
 * (EMAIL_ALREADY_REGISTERED, idUser=null) para que Residential marque el vínculo
 * FAILED con motivo, sin recrear ni re-enviar correo.
 *
 * <p>Sin efectos laterales dentro de la transacción: el envío SMTP y la publicación
 * del evento de retorno ocurren en {@code afterCommit} (o de inmediato si no hay
 * sincronización de transacción activa, p. ej. en tests unitarios).
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
            log.warn("El correo ya está registrado ({}). No se crea usuario; se publica el retorno REJECTED.", email);
            registerAfterCommit(ResidentUserCreatedEvent.rejectedDuplicateEmail(email, event.unitId(), UUID.randomUUID().toString()),
                    null);
            return;
        }

        var temporaryPassword = generateTemporaryPassword(event.fullName(), email);
        var documentType = parseDocumentType(event.documentType());

        Optional<User> created;
        try {
            // handle(SignUpCommand) ya persiste el User (userRepository.save); el agregado
            // devuelto es una entidad gestionada en este mismo persistence context, así que
            // activate() se propaga al commit por dirty checking. No hay segundo save.
            created = userCommandService.handle(new SignUpCommand(
                    event.fullName(),
                    email,
                    temporaryPassword,
                    event.phone(),
                    documentType,
                    event.documentNumber(),
                    List.of(new Role(Roles.OWNER))
            ));
        } catch (IllegalArgumentException e) {
            // Carrera: el correo se registró entre el pre-chequeo de arriba (o el pre-chequeo
            // Residential→IAM) y la creación. Si el usuario ya existe, es un duplicado de email:
            // publica REJECTED sin relanzar (la transacción confirma sin usuario y el afterCommit lo publica).
            // Si no existe, el fallo es otro (p. ej. rol OWNER ausente o documento duplicado) y debe ir a retry → DLQ.
            if (userRepository.findByEmail(email).isPresent()) {
                log.warn("El correo {} quedó registrado durante la provisión; se publica el retorno REJECTED.", email);
                registerAfterCommit(ResidentUserCreatedEvent.rejectedDuplicateEmail(email, event.unitId(), UUID.randomUUID().toString()),
                        null);
                return;
            }
            throw e;
        }
        var user = created.orElseThrow(() -> new IllegalStateException("No se pudo crear el usuario residente"));

        user.activate();
        log.info("Usuario OWNER creado para residente {} (id={})", email, user.getId());

        registerAfterCommit(ResidentUserCreatedEvent.created(email, event.unitId(), user.getId(), UUID.randomUUID().toString()),
                new CredentialsMailRequest(email, event.fullName(), temporaryPassword));
    }

    /**
     * Registra mail (si aplica) + publicación del evento de retorno en un único afterCommit:
     * el correo solo parte cuando el usuario ya quedó persistido y la publicación del
     * retorno también va después del commit. Sin sincronización activa (tests unitarios),
     * ambos se ejecutan de inmediato.
     */
    private void registerAfterCommit(ResidentUserCreatedEvent userCreated,
                                     CredentialsMailRequest mail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runAfterCommit(userCreated, mail);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runAfterCommit(userCreated, mail);
            }
        });
    }

    private void runAfterCommit(ResidentUserCreatedEvent userCreated, CredentialsMailRequest mail) {
        if (mail != null) {
            try {
                credentialsMailService.sendCredentials(mail.to(), mail.fullName(), mail.temporaryPassword());
            } catch (Exception e) {
                // El correo es post-commit best effort; el vínculo no debe quedarse sin retorno por un fallo SMTP.
                log.error("No se pudo enviar el correo de credenciales a {}: {}", mail.to(), e.getMessage());
            }
        }
        publishUserCreated(userCreated);
    }

    private void publishUserCreated(ResidentUserCreatedEvent userCreated) {
        rabbitTemplate.convertAndSend(USER_CREATED_EXCHANGE, USER_CREATED_ROUTING_KEY, userCreated);
        log.info("Publicado {} (email={}, unitId={}, idUser={}, status={}, reason={})", USER_CREATED_ROUTING_KEY,
                userCreated.email(), userCreated.unitId(), userCreated.idUser(), userCreated.status(), userCreated.reason());
    }

    private record CredentialsMailRequest(String to, String fullName, String temporaryPassword) {
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
