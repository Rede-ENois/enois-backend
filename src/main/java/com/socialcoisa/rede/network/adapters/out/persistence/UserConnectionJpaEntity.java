package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "user_connections", uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_connection_pair", columnNames = { "requester_user_id", "receiver_user_id" }) },
		indexes = {
			@Index(name = "idx_user_connections_requester_status", columnList = "requester_user_id, status"),
			@Index(name = "idx_user_connections_receiver_status", columnList = "receiver_user_id, status") })
class UserConnectionJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "requester_user_id", nullable = false)
	private String requesterUserId;

	@Column(name = "receiver_user_id", nullable = false)
	private String receiverUserId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConnectionStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected UserConnectionJpaEntity() {
	}

	static UserConnectionJpaEntity fromDomain(UserConnection connection) {
		UserConnectionJpaEntity entity = new UserConnectionJpaEntity();
		entity.id = connection.id();
		entity.requesterUserId = connection.requesterUserId();
		entity.receiverUserId = connection.receiverUserId();
		entity.status = connection.status();
		entity.createdAt = connection.createdAt();
		entity.acceptedAt = connection.acceptedAt();
		entity.removedAt = connection.removedAt();
		return entity;
	}

	UserConnection toDomain() {
		return UserConnection.restore(id, requesterUserId, receiverUserId, status, createdAt, acceptedAt, removedAt);
	}

}
