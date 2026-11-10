package com.edifika.iam.authentication.infrastructure.messaging;

import com.edifika.iam.authentication.application.internal.services.ResidentProvisioningService;
import com.edifika.iam.authentication.infrastructure.messaging.events.ResidentAssignedToUnitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consume residential.resident.assigned: crea el usuario OWNER, envía credenciales
 * y publica iam.resident.user-created. Reintentos (3x) → DLQ; idempotente por email.
 */
@Component
public class ResidentProvisioningConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResidentProvisioningConsumer.class);

    private final ResidentProvisioningService provisioningService;

    public ResidentProvisioningConsumer(ResidentProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @RabbitListener(queues = RabbitMqConfiguration.PROVISIONING_QUEUE)
    public void onResidentAssignedToUnit(ResidentAssignedToUnitEvent event) {
        log.info("Received residential.resident.assigned (email={}, unitId={}, messageId={})",
                event.email(), event.unitId(), event.messageId());
        provisioningService.provision(event);
    }
}
