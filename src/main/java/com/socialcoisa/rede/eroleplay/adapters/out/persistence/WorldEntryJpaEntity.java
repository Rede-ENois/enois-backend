package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.eroleplay.domain.WorldEntry;
import com.socialcoisa.rede.eroleplay.domain.WorldKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A linha da entidade de mundo. O id da ficha vinculada é gravado (é o endereço interno),
 * mas nunca sai na API — a projeção converte em nome de personagem.
 */
@Entity
@Table(name = "roleplay_world_entries")
class WorldEntryJpaEntity {

	/* Mesma regra da biblioteca: o nome da coluna é declarado, não deduzido do ambiente. */
	@Id
	@Column(name = "id")
	String id;

	@Column(name = "campaign_id")
	String campaignId;

	@Column(name = "kind")
	String kind;

	@Column(name = "title")
	String title;

	@Column(name = "body", columnDefinition = "longtext")
	String body;

	@Column(name = "tags")
	String tags;

	@Column(name = "linked_sheet_id")
	String linkedSheetId;

	@Column(name = "author_id")
	String authorId;

	@Column(name = "created_at")
	Instant createdAt;

	@Column(name = "updated_at")
	Instant updatedAt;

	static WorldEntryJpaEntity fromDomain(WorldEntry entry) {
		WorldEntryJpaEntity entity = new WorldEntryJpaEntity();
		entity.id = entry.id();
		entity.campaignId = entry.campaignId();
		entity.kind = entry.kind().name();
		entity.title = entry.title();
		entity.body = entry.body();
		entity.tags = String.join(",", entry.tags());
		entity.linkedSheetId = entry.linkedSheetId();
		entity.authorId = entry.authorId();
		entity.createdAt = entry.createdAt();
		entity.updatedAt = entry.updatedAt();
		return entity;
	}

	WorldEntry toDomain() {
		return WorldEntry.restore(id, campaignId, WorldKind.valueOf(kind), title, body, toTagList(), linkedSheetId,
			authorId, createdAt, updatedAt);
	}

	private List<String> toTagList() {
		if (tags == null || tags.isBlank()) {
			return List.of();
		}
		return new ArrayList<>(List.of(tags.split(",")));
	}
}
