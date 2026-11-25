package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.portfolio.domain.PortfolioPage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "portfolio_pages")
class PortfolioPageJpaEntity {

	@Id
	@Column(name = "user_id", nullable = false, length = 255)
	private String userId;

	@Column(name = "draft_html", columnDefinition = "longtext")
	private String draftHtml;

	@Column(name = "published_version", nullable = false)
	private int publishedVersion;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PortfolioPageJpaEntity() {
	}

	static PortfolioPageJpaEntity fromDomain(PortfolioPage page) {
		PortfolioPageJpaEntity entity = new PortfolioPageJpaEntity();
		entity.userId = page.userId();
		entity.draftHtml = page.draftHtml();
		entity.publishedVersion = page.publishedVersion();
		entity.publishedAt = page.publishedAt();
		entity.updatedAt = page.updatedAt();
		return entity;
	}

	PortfolioPage toDomain() {
		return new PortfolioPage(userId, draftHtml, publishedVersion, publishedAt, updatedAt);
	}
}
