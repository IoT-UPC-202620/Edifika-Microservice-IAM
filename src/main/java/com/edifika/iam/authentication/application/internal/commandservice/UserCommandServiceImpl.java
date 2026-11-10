package com.edifika.iam.authentication.application.internal.commandservice;


import com.edifika.iam.authentication.application.internal.outboundservices.hashing.HashingService;
import com.edifika.iam.authentication.application.internal.outboundservices.tokens.TokenService;
import com.edifika.iam.authentication.domain.model.aggregates.User;
import com.edifika.iam.authentication.domain.model.commands.*;


import com.edifika.iam.authentication.domain.model.valueobjects.Roles;
import com.edifika.iam.authentication.domain.services.UserCommandService;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.RoleRepository;
import com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories.UserRepository;
import org.apache.commons.lang3.tuple.ImmutablePair;

import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementación de los comandos del módulo IAM.
 * Maneja registro, login, actualización y eliminación de usuarios.
 */
@Service
public class UserCommandServiceImpl implements UserCommandService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final HashingService hashingService;
    private final TokenService tokenService;

    public UserCommandServiceImpl(UserRepository userRepository,
                                  RoleRepository roleRepository,
                                  HashingService hashingService,
                                  TokenService tokenService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.hashingService = hashingService;
        this.tokenService = tokenService;
    }

    @Override
    public Optional<ImmutablePair<User, String>> handle(SignInCommand signInCommand) {
        var user = userRepository.findByEmail(signInCommand.email());
        if (user.isEmpty())
            throw new IllegalArgumentException("Usuario con email " + signInCommand.email() + " no encontrado");
        if (!hashingService.matches(signInCommand.password(), user.get().getPasswordHash()))
            throw new IllegalArgumentException("Contraseña incorrecta");

        var authenticatedUser = user.get();
        var roles = authenticatedUser.getRoles().stream()
                .map(role -> role.getStringName())
                .collect(Collectors.toList());
        var token = tokenService.generateToken(
                authenticatedUser.getEmail(),
                authenticatedUser.getId(),
                roles);
        return Optional.of(ImmutablePair.of(authenticatedUser, token));
    }

    @Override
    public Optional<User> handle(SignUpCommand signUpCommand) {
        if (userRepository.existsByEmail(signUpCommand.email()))
            throw new IllegalArgumentException("El correo ya está registrado");

        if (userRepository.existsByDocumentNumber(signUpCommand.documentNumber()))
            throw new IllegalArgumentException("El número de documento " + signUpCommand.documentNumber() + " ya está registrado");

        var roles = signUpCommand.roles().stream().map(
                role -> roleRepository.findByName(role.getName())
                        .orElseThrow(() -> new IllegalArgumentException("Rol no encontrado: " + role.getName()))
        ).toList();

        var user = new User(
                signUpCommand.fullName(),
                signUpCommand.email(),
                hashingService.encode(signUpCommand.password()),
                signUpCommand.phone(),
                signUpCommand.documentType(),
                signUpCommand.documentNumber(),
                roles
        );
        userRepository.save(user);
        return userRepository.findByEmail(signUpCommand.email());
    }

    @Override
    public Optional<User> handle(UpdateUserCommand updateUserCommand, Long userId) {
        var userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty())
            throw new IllegalArgumentException("Usuario con ID " + userId + " no encontrado");

        var userToUpdate = userOptional.get();

        userToUpdate.updateFullName(updateUserCommand.fullName());
        userToUpdate.updateEmail(updateUserCommand.email());

        if (updateUserCommand.phone() != null && !updateUserCommand.phone().isBlank())
            userToUpdate.updatePhone(updateUserCommand.phone());

        if (updateUserCommand.documentType() != null)
            userToUpdate.updateDocumentType(updateUserCommand.documentType());

        if (updateUserCommand.documentNumber() != null && !updateUserCommand.documentNumber().isBlank())
            userToUpdate.updateDocumentNumber(updateUserCommand.documentNumber());

        if (updateUserCommand.password() != null && !updateUserCommand.password().isBlank())
            userToUpdate.changePassword(hashingService.encode(updateUserCommand.password()));

        try {
            return Optional.of(userRepository.save(userToUpdate));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void handle(DeleteUserCommand deleteUserCommand) {
        if (!userRepository.existsById(deleteUserCommand.id()))
            throw new IllegalArgumentException("Usuario con ID " + deleteUserCommand.id() + " no encontrado");
        try {
            userRepository.deleteById(deleteUserCommand.id());
        } catch (Exception e) {
            throw new RuntimeException("Error al eliminar usuario: " + e.getMessage(), e);
        }
    }
}