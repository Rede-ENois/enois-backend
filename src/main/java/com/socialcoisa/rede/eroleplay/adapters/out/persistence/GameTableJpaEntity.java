package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.GameTable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roleplay_tables")
class GameTableJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	/** A sala de voz do Epapo vinculada; nulo quando a mesa ainda nao tem voz. */
	@Column(name = "voice_room_id", length = 120)
	private String voiceRoomId;

	@Column(nullable = false)
	private boolean open;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GameTableJpaEntity() {
	}

	static GameTableJpaEntity fromDomain(GameTable table) {
		GameTableJpaEntity entity = new GameTableJpaEntity();
		entity.id = table.id();
		entity.campaignId = table.campaignId();
		entity.name = table.name();
		entity.ownerId = table.ownerId();
		entity.voiceRoomId = table.voiceRoomId();
		entity.open = table.isOpen();
		entity.createdAt = table.createdAt();
		entity.updatedAt = table.updatedAt();
		return entity;
	}

	GameTable toDomain() {
		return GameTable.restore(id, campaignId, name, ownerId, voiceRoomId, open, createdAt, updatedAt);
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}
}
