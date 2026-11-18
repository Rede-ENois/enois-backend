package com.socialcoisa.rede.blog.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.util.StringUtils;

import com.socialcoisa.rede.blog.domain.BlogPost;
import com.socialcoisa.rede.blog.domain.BlogPostFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "blog_posts")
class BlogPostJpaEntity {

	@Id
	private String id;

	@Column(name = "blog_id", nullable = false)
	private String blogId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(name = "body_format", nullable = false, length = 16)
	private BlogPostFormat bodyFormat;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "media_ids", length = 1000)
	private String mediaIds;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BlogPostJpaEntity() {
	}

	static BlogPostJpaEntity fromDomain(BlogPost post) {
		BlogPostJpaEntity e = new BlogPostJpaEntity();
		e.id = post.id();
		e.blogId = post.blogId();
		e.userId = post.userId();
		e.title = post.title();
		e.body = post.body();
		e.bodyFormat = post.bodyFormat();
		e.mediaIds = post.mediaIds() == null || post.mediaIds().isEmpty() ? null
			: String.join(",", post.mediaIds());
		e.publishedAt = post.publishedAt();
		e.createdAt = post.publishedAt();
		e.updatedAt = post.updatedAt();
		return e;
	}

	BlogPost toDomain() {
		List<String> media = StringUtils.hasText(mediaIds)
			? Arrays.stream(mediaIds.split(",")).map(String::trim).filter(StringUtils::hasText).toList()
			: List.of();
		return BlogPost.restore(id, blogId, userId, title, body, bodyFormat, media, publishedAt, updatedAt);
	}
}
