package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Listas viajam como CSV (convenção das listas no banco, como nos posts agendados). */
@Entity
@Table(name = "draft_posts")
class DraftPostJpaEntity {

	@Id
	String id;

	@Column(name = "user_id", nullable = false, unique = true)
	String userId;

	@Column(name = "post_type", nullable = false)
	String postType;

	@Column(columnDefinition = "text")
	String content;

	@Column(name = "page_html", columnDefinition = "longtext")
	String pageHtml;

	@Column(name = "article_body", columnDefinition = "text")
	String articleBody;

	/** Assunto declarado do post avulso (o de comunidade herda o dela na publicação). */
	@Column(name = "topic", length = 40)
	String topic;

	/** Alternativas da enquete (POLL), como CSV — podem estar pela metade, é um rascunho. */
	@Column(name = "poll_options", columnDefinition = "text")
	String pollOptions;

	@Column(name = "poll_multiple_choice", nullable = false)
	boolean pollMultipleChoice;

	@Column(name = "poll_ends_at")
	Instant pollEndsAt;

	@Column(name = "mentioned_ids", columnDefinition = "text")
	String mentionedIds;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;
}
