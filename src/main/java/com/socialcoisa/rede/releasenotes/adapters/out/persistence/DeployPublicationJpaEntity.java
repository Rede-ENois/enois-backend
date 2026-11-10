package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row per deploy that published the "what's new" (see V268). */
@Entity
@Table(name = "deploy_publication")
class DeployPublicationJpaEntity {

	@Id
	@Column(name = "commit_sha", length = 40)
	private String commitSha;

	/** The items as a JSON array of {title, description} — what this deploy put on air. */
	@Column(name = "items_json", nullable = false, columnDefinition = "longtext")
	private String itemsJson;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	protected DeployPublicationJpaEntity() {
	}

	DeployPublicationJpaEntity(String commitSha, String itemsJson, Instant publishedAt) {
		this.commitSha = commitSha;
		this.itemsJson = itemsJson;
		this.publishedAt = publishedAt;
	}

	String commitSha() {
		return commitSha;
	}

	String itemsJson() {
		return itemsJson;
	}

	Instant publishedAt() {
		return publishedAt;
	}
}
