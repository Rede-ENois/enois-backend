package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;
import com.socialcoisa.rede.contributions.domain.UserSeal;

@Repository
class UserSealPersistenceAdapter implements UserSealRepository {

	private final SpringDataUserSealJpaRepository repository;

	UserSealPersistenceAdapter(SpringDataUserSealJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public UserSeal save(UserSeal seal) {
		return repository.save(UserSealJpaEntity.fromDomain(seal)).toDomain();
	}

	@Override
	public Optional<UserSeal> findByUserAndType(String userId, SealType type) {
		return repository.findByUserIdAndSealType(userId, type).map(UserSealJpaEntity::toDomain);
	}

	@Override
	public List<UserSeal> findActiveByUser(String userId) {
		return repository.findByUserIdAndStatus(userId, SealStatus.ACTIVE).stream()
			.map(UserSealJpaEntity::toDomain).toList();
	}
}
