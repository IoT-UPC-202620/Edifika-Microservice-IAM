package com.edifika.iam.authentication.application.internal.services;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Contrasena temporal que cumple la politica de SignUpCommand. La usan la provision por evento
 * (ResidentProvisioningService) y el alta directa de residentes (ResidentUserService).
 */
@Component
public class TemporaryPasswordGenerator {

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();

    public String generate(String fullName, String email) {
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
