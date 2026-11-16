package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A ficha de uma pessoa numa campanha (uma linha por par campanha + pessoa).
 *
 * <p>{@code entries_json} guarda os valores já validados e normalizados pelo servidor, cada um com
 * a visibilidade efetiva. Derivado não entra aqui: número que o sistema calcula é recalculado na
 * leitura, para a ficha não poder mentir sobre a própria regra.</p>
 */
@Entity
@Table(name = "roleplay_character_sheets")
class CharacterSheetJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "entries_json", nullable = false, columnDefinition = "longtext")
	private String entriesJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CharacterSheetJpaEntity() {
	}

	CharacterSheetJpaEntity(String id, String campaignId, String userId, String name, String entriesJson,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.userId = userId;
		this.name = name;
		this.entriesJson = entriesJson;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	String userId() {
		return userId;
	}

	String name() {
		return name;
	}

	String entriesJson() {
		return entriesJson;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}
}