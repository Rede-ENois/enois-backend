package com.socialcoisa.rede.games.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.socialcoisa.rede.games.domain.GameProgress;

@Entity
@Table(name = "game_progress",
		uniqueConstraints = @UniqueConstraint(name = "uk_game_progress_user", columnNames = { "game_id", "user_id" }),
		indexes = @Index(name = "idx_game_progress_user", columnList = "user_id, updated_at"))
class GameProgressJpaEntity {

	@Id
	private String id;

	@Column(name = "game_id", nullable = false)
	private String gameId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "state", nullable = false, columnDefinition = "longtext")
	private String state;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GameProgressJpaEntity() {
	}

	static GameProgressJpaEntity fromDomain(GameProgress progress) {
		GameProgressJpaEntity entity = new GameProgressJpaEntity();
		entity.id = progress.id();
		entity.gameId = progress.gameId();
		entity.userId = progress.userId();
		entity.state = progress.state();
		entity.createdAt = progress.createdAt();
		entity.updatedAt = progress.updatedAt();
		return entity;
	}

	GameProgress toDomain() {
		return new GameProgress(id, gameId, userId, state, createdAt, updatedAt);
	}
}
