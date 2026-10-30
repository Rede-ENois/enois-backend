package com.socialcoisa.rede.corporate.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Repository
public class CorporateProfilePersistenceAdapter implements CorporateProfileRepository {

	/** Índice único do @ (V107). O MariaDB cita o nome em minúsculas e o H2 em maiúsculas. */
	private static final String USERNAME_UNIQUE = "uk_corporate_profiles_username";

	private final SpringDataCorporateProfileJpaRepository repository;

	CorporateProfilePersistenceAdapter(SpringDataCorporateProfileJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public CorporateProfile save(CorporateProfile profile) {
		try {
			// O id é o user_id: o merge do JPA insere quando não existe e atualiza quando existe.
			// A corrida no @ profissional é resolvida aqui: o índice único do banco manda.
			return repository.saveAndFlush(CorporateProfileJpaEntity.fromDomain(profile)).toDomain();
		}
		catch (DataIntegrityViolationException e) {
			// Só a violação do índice do @ vira 409; outro erro de gravação (valor que a coluna
			// recusa, por exemplo) sobe como está em vez de se passar por conflito de @.
			if (violaUsernameUnico(e)) {
				throw new ApiException(409, "USERNAME_TAKEN",
					"Esse usuário profissional já está em uso. Escolha outro.");
			}
			throw e;
		}
	}

	private static boolean violaUsernameUnico(DataIntegrityViolationException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& violation.getConstraintName() != null
					&& violation.getConstraintName().toLowerCase(Locale.ROOT).contains(USERNAME_UNIQUE)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Optional<CorporateProfile> findByUserId(String userId) {
		return repository.findById(userId).map(CorporateProfileJpaEntity::toDomain);
	}

	@Override
	public Optional<CorporateProfile> findByUsername(String username) {
		return repository.findByUsername(username).map(CorporateProfileJpaEntity::toDomain);
	}

	@Override
	public List<CorporateProfile> findByUserIds(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return repository.findAllById(userIds).stream()
			.map(CorporateProfileJpaEntity::toDomain)
			.toList();
	}

	@Override
	public boolean existsByUsername(String username) {
		return repository.existsByUsername(username);
	}
}
