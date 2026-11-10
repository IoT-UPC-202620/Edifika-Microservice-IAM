package com.edifika.iam.authentication.infrastructure.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envía el correo de credenciales del residente provisionado.
 * En dev se apunta a Mailpit (SMTP 1025 / UI 8025) por defecto.
 */
@Service
public class CredentialsMailService {

    private static final Logger log = LoggerFactory.getLogger(CredentialsMailService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String appUrl;

    public CredentialsMailService(JavaMailSender mailSender,
                                  @Value("${edifika.mail.from:no-reply@edifika.com}") String from,
                                  @Value("${edifika.app.url:http://localhost:4200}") String appUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.appUrl = appUrl;
    }

    public void sendCredentials(String to, String fullName, String temporaryPassword) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Bienvenido a Edifika — tus credenciales de acceso");
        message.setText("""
                Hola %s,

                Tu administrador te registró como residente. Estas son tus credenciales de acceso:

                Usuario: %s
                Contraseña temporal: %s

                Ingresa en: %s
                Por seguridad, cambia tu contraseña en el primer ingreso.

                El equipo Edifika
                """.formatted(fullName, to, temporaryPassword, appUrl));
        mailSender.send(message);
        log.info("Credenciales enviadas a {}", to);
    }
}
