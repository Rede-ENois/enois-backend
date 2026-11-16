package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.network.domain.NetworkMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_state")
class NetworkStateJpaEntity {

	@Id
	@Column(length = 20)
	private String id;

	@Column(nullable = false, length = 24)
	private String mode;

	@Column(length = 200)
	private String reason;

	@Column(nullable = false)
	private Instant since;

	protected NetworkStateJpaEntity() {
	}

	NetworkStateJpaEntity(String id, String mode, String reason, Instant since) {
		this.id = id;
		this.mode = mode;
		this.reason = reason;
		this.since = since;
	}

	String mode() {
		return mode;
	}

	String reason() {
		return reason;
	}

	Instant since() {
		return since;
	}

	void change(String mode, String reason, Instant since) {
		this.mode = mode;
		this.reason = reason;
		this.since = since;
	}

	NetworkMode toMode() {
		return NetworkMode.valueOf(mode);
	}
}
