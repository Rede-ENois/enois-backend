package com.socialcoisa.rede.network.domain;

import java.time.Instant;

public class UserConnection {

	private final Long id;
	private final String requesterUserId;
	private final String receiverUserId;
	private final ConnectionStatus status;
	private final Instant createdAt;
	private final Instant acceptedAt;
	private final Instant removedAt;

	private UserConnection(Long id, String requesterUserId, String receiverUserId, ConnectionStatus status,
			Instant createdAt, Instant acceptedAt, Instant removedAt) {
		this.id = id;
		this.requesterUserId = requesterUserId;
		this.receiverUserId = receiverUserId;
		this.status = status;
		this.createdAt = createdAt;
		this.acceptedAt = acceptedAt;
		this.removedAt = removedAt;
	}

	public static UserConnection request(String requesterUserId, String receiverUserId, Instant now) {
		return new UserConnection(null, requesterUserId, receiverUserId, ConnectionStatus.PENDING, now, null, null);
	}

	public static UserConnection restore(Long id, String requesterUserId, String receiverUserId,
			ConnectionStatus status, Instant createdAt, Instant acceptedAt, Instant removedAt) {
		return new UserConnection(id, requesterUserId, receiverUserId, status, createdAt, acceptedAt, removedAt);
	}

	public UserConnection accept(Instant now) {
		return new UserConnection(id, requesterUserId, receiverUserId, ConnectionStatus.ACCEPTED, createdAt, now, null);
	}

	public UserConnection remove(Instant now) {
		return new UserConnection(id, requesterUserId, receiverUserId, ConnectionStatus.REMOVED, createdAt, acceptedAt,
			now);
	}

	public Long id() {
		return id;
	}

	public String requesterUserId() {
		return requesterUserId;
	}

	public String receiverUserId() {
		return receiverUserId;
	}

	public ConnectionStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant acceptedAt() {
		return acceptedAt;
	}

	public Instant removedAt() {
		return removedAt;
	}

}
