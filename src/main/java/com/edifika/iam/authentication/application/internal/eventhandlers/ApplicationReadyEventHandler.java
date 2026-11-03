package com.edifika.iam.authentication.application.internal.eventhandlers;

import com.edifika.iam.authentication.application.internal.seeding.DefaultUsersSeeder;
import com.edifika.iam.authentication.domain.model.commands.SeedRolesCommand;

import com.edifika.iam.authentication.domain.services.RoleCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;

/**
 * Manejador de eventos que se ejecuta al iniciar la aplicación.
 * Inicializa los roles y los usuarios por defecto (administrador y propietario).
 */
@Service
public class ApplicationReadyEventHandler {

    private final RoleCommandService roleCommandService;
    private final DefaultUsersSeeder defaultUsersSeeder;
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationReadyEventHandler.class);

    public ApplicationReadyEventHandler(RoleCommandService roleCommandService, DefaultUsersSeeder defaultUsersSeeder) {
        this.roleCommandService = roleCommandService;
        this.defaultUsersSeeder = defaultUsersSeeder;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        var applicationName = event.getApplicationContext().getId();
        LOGGER.info("Verificando si se necesita inicializar roles para {} en {}", applicationName, currentTimestamp());
        roleCommandService.handle(new SeedRolesCommand());
        LOGGER.info("Roles inicializados correctamente para {} en {}", applicationName, currentTimestamp());
        defaultUsersSeeder.seed();
    }

    private Timestamp currentTimestamp() {
        return new Timestamp(System.currentTimeMillis());
    }
}
