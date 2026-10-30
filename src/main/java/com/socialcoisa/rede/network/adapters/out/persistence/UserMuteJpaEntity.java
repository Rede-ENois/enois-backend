package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.network.domain.UserMute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_mutes", uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_mutes_pair", columnNames = { "muter_id", "muted_id" }) },
		indexes = {
			@Index(name = "idx_user_mutes_muter", columnList = "muter_id") })
class UserMuteJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "muter_id", nullable = false)
	private String muterId;

	@Column(name = "muted_id", nullable = false)
	private String mutedId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserMuteJpaEntity() {
	}

	static UserMuteJpaEntity fromDomain(UserMute mute) {
		UserMuteJpaEntity entity = new UserMuteJpaEntity();
		entity.id = mute.id();
		entity.muterId = mute.muterId();
		entity.mutedId = mute.mutedId();
		entity.createdAt = mute.createdAt();
		return entity;
	}

	UserMute toDomain() {
		return UserMute.restore(id, muterId, mutedId, createdAt);
	}
}
