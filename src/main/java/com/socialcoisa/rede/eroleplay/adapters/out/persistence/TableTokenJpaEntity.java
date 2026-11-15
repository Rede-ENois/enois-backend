package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.TableToken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** O token posicionado na mesa; a linha inteira é regravada a cada movimento. */
@Entity
@Table(name = "roleplay_table_tokens")
class TableTokenJpaEntity {

	@Id
	private String id;

	@Column(name = "table_id", nullable = false)
	private String tableId;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "owner_id")
	private String ownerId;

	@Column(nullable = false, length = 60)
	private String label;

	@Column(nullable = false, length = 9)
	private String color;

	@Column(name = "pos_x", nullable = false)
	private int posX;

	@Column(name = "pos_y", nullable = false)
	private int posY;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TableTokenJpaEntity() {
	}

	static TableTokenJpaEntity from(TableToken token) {
		TableTokenJpaEntity entity = new TableTokenJpaEntity();
		entity.id = token.id();
		entity.tableId = token.tableId();
		entity.campaignId = token.campaignId();
		entity.ownerId = token.ownerId();
		entity.label = token.label();
		entity.color = token.color();
		entity.posX = token.posX();
		entity.posY = token.posY();
		entity.createdAt = token.createdAt();
		entity.updatedAt = token.updatedAt();
		return entity;
	}

	TableToken toDomain() {
		return TableToken.restore(id, tableId, campaignId, ownerId, label, color, posX, posY, createdAt, updatedAt);
	}
}
