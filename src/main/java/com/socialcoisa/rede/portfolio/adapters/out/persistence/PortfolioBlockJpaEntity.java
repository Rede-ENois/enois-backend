package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "portfolio_blocks")
class PortfolioBlockJpaEntity {

	@Id
	@Column(name = "id", nullable = false, length = 40)
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "position", nullable = false)
	private int position;

	@Enumerated(EnumType.STRING)
	@Column(name = "block_type", nullable = false, length = 20)
	private PortfolioBlockType type;

	@Column(length = 160)
	private String title;

	@Column(name = "text", length = 4000)
	private String text;

	@Column(name = "project_id", length = 40)
	private String projectId;

	@Column(name = "media_id", length = 64)
	private String mediaId;

	@Column(name = "link_url", length = 1000)
	private String linkUrl;

	/** {@code null} = nunca publicado; a publicação carimba a versão da página. */
	@Column(name = "published_version")
	private Integer publishedVersion;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PortfolioBlockJpaEntity() {
	}

	static PortfolioBlockJpaEntity fromDomain(PortfolioBlock block) {
		PortfolioBlockJpaEntity entity = new PortfolioBlockJpaEntity();
		entity.id = block.id();
		entity.userId = block.userId();
		entity.position = block.position();
		entity.type = block.type();
		entity.title = block.title();
		entity.text = block.text();
		entity.projectId = block.projectId();
		entity.mediaId = block.mediaId();
		entity.linkUrl = block.linkUrl();
		entity.publishedVersion = block.publishedVersion();
		entity.createdAt = block.createdAt();
		entity.updatedAt = block.updatedAt();
		return entity;
	}

	PortfolioBlock toDomain() {
		return new PortfolioBlock(id, userId, position, type, title, text, projectId, mediaId, linkUrl,
			publishedVersion, createdAt, updatedAt);
	}
}
