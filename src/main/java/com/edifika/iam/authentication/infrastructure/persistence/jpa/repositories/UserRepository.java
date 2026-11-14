package com.edifika.iam.authentication.infrastructure.persistence.jpa.repositories;

import com.edifika.iam.authentication.domain.model.aggregates.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.edifika.iam.authentication.domain.model.valueobjects.Roles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la entidad User.
 * Permite buscar usuarios por email y verificar su existencia.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByDocumentNumber(String documentNumber);
    Optional<User> findByDocumentNumber(String documentNumber);

    // Busqueda de residentes (solo los roles indicados), maximo 20 resultados
    List<User> findDistinctTop20ByDocumentNumberStartingWithAndRoles_NameIn(String documentNumber, Collection<Roles> roles);

    List<User> findDistinctTop20ByFullNameContainingIgnoreCaseAndRoles_NameIn(String fullName, Collection<Roles> roles);
}
