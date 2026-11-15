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

	@Id
	String id;

	String campaignId;

	String sheetId;

	String ownerId;

	@Column(length = 120)
	String characterName;

	String gameSystem;

	@Column(columnDefinition = "longtext")
	String fieldsJson;

	Instant publishedAt;

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
