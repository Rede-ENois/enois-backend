package com.socialcoisa.rede.network.adapters.out.persistence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.UserMuteRepository;
import com.socialcoisa.rede.network.domain.UserMute;

@Repository
class UserMutePersistenceAdapter implements UserMuteRepository {

	private final SpringDataUserMuteJpaRepository repository;

	UserMutePersistenceAdapter(SpringDataUserMuteJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(UserMute mute) {
		repository.findByMuterIdAndMutedId(mute.muterId(), mute.mutedId())
			.ifPresentOrElse(existing -> { }, () -> repository.save(UserMuteJpaEntity.fromDomain(mute)));
	}

	@Override
	public void delete(String muterId, String mutedId) {
		repository.findByMuterIdAndMutedId(muterId, mutedId)
			.ifPresent(repository::delete);
	}

	@Override
	public boolean exists(String muterId, String mutedId) {
		return repository.findByMuterIdAndMutedId(muterId, mutedId).isPresent();
	}

	@Override
	public List<UserMute> findByMuterId(String muterId) {
		return repository.findByMuterIdOrderByCreatedAtDesc(muterId).stream()
			.map(UserMuteJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Set<String> findMutedIds(String muterId) {
		return repository.findByMuterIdOrderByCreatedAtDesc(muterId).stream()
			.map(UserMuteJpaEntity::toDomain)
			.map(UserMute::mutedId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}
}
