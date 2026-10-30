package com.socialcoisa.rede.notices.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.notices.domain.AdminNotice;
import com.socialcoisa.rede.notices.domain.NoticeSurface;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A linha única do aviso vigente (id fixo; ver V197). */
@Entity
@Table(name = "admin_notice")
class AdminNoticeJpaEntity {

	@Id
	@Column(name = "id", length = 32)
	private String id;

	@Column(nullable = false, length = 140)
	private String title;

	/** HTML sanitizado do corpo: varchar não comporta uma página colada (mesma razão de posts.page_html). */
	@Column(name = "body_html", nullable = false, columnDefinition = "longtext")
	private String bodyHtml;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private NoticeSurface surface;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AdminNoticeJpaEntity() {
	}

	AdminNoticeJpaEntity(AdminNotice notice) {
		this.id = notice.id();
		this.title = notice.title();
		this.bodyHtml = notice.bodyHtml();
		this.surface = notice.surface();
		this.publishedAt = notice.publishedAt();
		this.updatedAt = notice.updatedAt();
	}

	AdminNotice toDomain() {
		return AdminNotice.of(id, title, bodyHtml, surface, publishedAt, updatedAt);
	}
}
