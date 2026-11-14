package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O modelo de ficha de uma campanha (uma linha por campanha).
 *
 * <p>{@code fields_json} guarda o descritor de cada campo como um retrato: chave, rótulo, tipo,
 * obrigatoriedade, opções e a visibilidade MÁXIMA. O retrato não envelhece porque trocar o sistema
 * de regras é recusado enquanto o modelo existe — a ficha foi escrita para aqueles campos.</p>
 */
@Entity
@Table(name = "roleplay_sheet_templates")
class SheetTemplateJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "game_system", nullable = false, length = 80)
	private String gameSystem;

	@Column(name = "fields_json", nullable = false, columnDefinition = "longtext")
	private String fieldsJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SheetTemplateJpaEntity() {
	}

	SheetTemplateJpaEntity(String id, String campaignId, String gameSystem, String fieldsJson, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.gameSystem = gameSystem;
		this.fieldsJson = fieldsJson;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	String gameSystem() {
		return gameSystem;
	}

	String fieldsJson() {
		return fieldsJson;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}
}