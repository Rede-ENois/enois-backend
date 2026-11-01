package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single row of the current release notes (fixed id; see V225). */
@Entity
@Table(name = "release_note")
class ReleaseNoteJpaEntity {

	@Id
	@Column(name = "id", length = 32)
	private String id;

	/** The items as a JSON array of {title, description}. */
	@Column(name = "items_json", nullable = false, columnDefinition = "longtext")
	private String itemsJson;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReleaseNoteJpaEntity() {
	}

	ReleaseNoteJpaEntity(String id, String itemsJson, Instant publishedAt, Instant updatedAt) {
		this.id = id;
		this.itemsJson = itemsJson;
		this.publishedAt = publishedAt;
		this.updatedAt = updatedAt;
	}

	String id() {
		return id;
	}

	String itemsJson() {
		return itemsJson;
	}

	Instant publishedAt() {
		return publishedAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}
}
