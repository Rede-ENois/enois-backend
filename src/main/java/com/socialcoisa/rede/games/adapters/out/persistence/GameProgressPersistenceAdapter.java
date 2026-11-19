package com.socialcoisa.rede.games.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.games.application.port.out.GameProgressRepository;
import com.socialcoisa.rede.games.domain.GameProgress;

@Component
class GameProgressPersistenceAdapter implements GameProgressRepository {

	private final SpringDataGameProgressJpaRepository repository;

	GameProgressPersistenceAdapter(SpringDataGameProgressJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public GameProgress save(GameProgress progress) {
		return repository.save(GameProgressJpaEntity.fromDomain(progress)).toDomain();
	}

	@Override
	public Optional<GameProgress> find(String gameId, String userId) {
		return repository.findByGameIdAndUserId(gameId, userId).map(GameProgressJpaEntity::toDomain);
	}
}
