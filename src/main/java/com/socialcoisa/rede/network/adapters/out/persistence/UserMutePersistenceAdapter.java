package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.UserMuteRepository;
import com.socialcoisa.rede.network.domain.UserMute;

/**
 * Le sempre pelos silenciamentos que ainda valem: um prazo vencido nao esconde mais ninguem, mesmo
 * que a linha continue na tabela. As linhas vencidas ficam onde estao (o `unmute` as remove) — o
 * filtro e de leitura, entao nada precisa de limpeza agendada para o comportamento correto.
 */
@Repository
class UserMutePersistenceAdapter implements UserMuteRepository {

	private final SpringDataUserMuteJpaRepository repository;
	private final Clock clock;

	UserMutePersistenceAdapter(SpringDataUserMuteJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void save(UserMute mute) {
		repository.findByMuterIdAndMutedId(mute.muterId(), mute.mutedId())
			.ifPresentOrElse(
				existing -> repository.save(existing.renew(mute.createdAt(), mute.expiresAt())),
				() -> repository.save(UserMuteJpaEntity.fromDomain(mute)));
	}

	@Override
	public void delete(String muterId, String mutedId) {
		repository.findByMuterIdAndMutedId(muterId, mutedId)
			.ifPresent(repository::delete);
	}

	@Override
	public boolean exists(String muterId, String mutedId) {
		return findActive(muterId, mutedId).isPresent();
	}

	@Override
	public java.util.Optional<UserMute> findActive(String muterId, String mutedId) {
		return repository.findActive(muterId, mutedId, clock.instant())
			.map(UserMuteJpaEntity::toDomain);
	}

	@Override
	public List<UserMute> findByMuterId(String muterId) {
		return repository.findActiveByMuterId(muterId, clock.instant()).stream()
			.map(UserMuteJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Set<String> findMutedIds(String muterId) {
		return repository.findActiveByMuterId(muterId, clock.instant()).stream()
			.map(UserMuteJpaEntity::toDomain)
			.map(UserMute::mutedId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}
}
