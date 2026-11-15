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

	@Id
	String id;

	String campaignId;

	String kind;

	String title;

	@Column(length = 2147483647)
	String body;

	String tags;

	String linkedSheetId;

	String authorId;

	Instant createdAt;

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
