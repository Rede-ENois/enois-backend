package com.socialcoisa.rede.blog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Um post do blog. O id dele e o MESMO id do post-ponteiro criado no feed (que so aparece para quem
 * segue o blog) — assim o card do feed linka direto para o post do blog sem guardar mais nada.
 */
public final class BlogPost {

	private final String id;
	private final String blogId;
	private final String userId;
	private String title;
	private String body;
	private BlogPostFormat bodyFormat;
	/** Mídia anexada ao post (vlog): ids separados por vírgula na coluna, lista aqui. */
	private List<String> mediaIds;
	private final Instant publishedAt;
	private Instant updatedAt;

	private BlogPost(String id, String blogId, String userId, String title, String body, BlogPostFormat bodyFormat,
			List<String> mediaIds, Instant publishedAt, Instant updatedAt) {
		this.id = id;
		this.blogId = blogId;
		this.userId = userId;
		this.title = title;
		this.body = body;
		this.bodyFormat = bodyFormat;
		this.mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
		this.publishedAt = publishedAt;
		this.updatedAt = updatedAt;
	}

	public static BlogPost publish(String id, String blogId, String userId, String title, String body,
			BlogPostFormat bodyFormat, List<String> mediaIds, Instant now) {
		return new BlogPost(id, blogId, userId, title, body, bodyFormat, mediaIds, now, now);
	}

	public static BlogPost restore(String id, String blogId, String userId, String title, String body,
			BlogPostFormat bodyFormat, List<String> mediaIds, Instant publishedAt, Instant updatedAt) {
		return new BlogPost(id, blogId, userId, title, body, bodyFormat, mediaIds, publishedAt, updatedAt);
	}

	public void edit(String title, String body, BlogPostFormat bodyFormat, List<String> mediaIds, Instant now) {
		this.title = title;
		this.body = body;
		this.bodyFormat = bodyFormat;
		this.mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
		this.updatedAt = now;
	}

	public List<String> mediaIds() {
		return mediaIds == null ? List.of() : mediaIds;
	}

	public String id() { return id; }

	public String blogId() { return blogId; }

	public String userId() { return userId; }

	public String title() { return title; }

	public String body() { return body; }

	public BlogPostFormat bodyFormat() { return bodyFormat; }

	public Instant publishedAt() { return publishedAt; }

	public Instant updatedAt() { return updatedAt; }
}
