package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.socialcoisa.rede.eroleplay.domain.LibrarySheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A linha da publicação na biblioteca. {@code sheet_id} é o endereço interno de volta à ficha
 * (só o serviço o usa — para conferir posse e republicar); nunca sai na resposta pública.
 */
@Entity
@Table(name = "roleplay_published_sheets")
class LibrarySheetJpaEntity {

	/*
	 * Todo campo nomeia a coluna. O boot valida o mapeamento contra o banco já migrado, e a
	 * estratégia física de nomes do ambiente não é a mesma em todo lugar: sem o nome explícito, o
	 * Hibernate procurou `campaignId` numa tabela cuja coluna é `campaign_id` e a QA não subiu.
	 */
	@Id
	@Column(name = "id")
	String id;

	@Column(name = "campaign_id")
	String campaignId;

	@Column(name = "sheet_id")
	String sheetId;

	@Column(name = "owner_id")
	String ownerId;

	@Column(name = "character_name", length = 120)
	String characterName;

	@Column(name = "game_system")
	String gameSystem;

	@Column(name = "fields_json", columnDefinition = "longtext")
	String fieldsJson;

	@Column(name = "published_at")
	Instant publishedAt;

	@Column(name = "updated_at")
	Instant updatedAt;

	static LibrarySheetJpaEntity fromDomain(LibrarySheet sheet, String fieldsJson) {
		LibrarySheetJpaEntity entity = new LibrarySheetJpaEntity();
		entity.id = sheet.id();
		entity.campaignId = sheet.campaignId();
		entity.sheetId = sheet.sheetId();
		entity.ownerId = sheet.ownerId();
		entity.characterName = sheet.characterName();
		entity.gameSystem = sheet.gameSystem();
		entity.fieldsJson = fieldsJson;
		entity.publishedAt = sheet.publishedAt();
		entity.updatedAt = sheet.updatedAt();
		return entity;
	}

	LibrarySheet toDomain(Map<String, String> fields) {
		return LibrarySheet.restore(id, campaignId, sheetId, ownerId, characterName, gameSystem, fields,
			publishedAt, updatedAt);
	}
}
