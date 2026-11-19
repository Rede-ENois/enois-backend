package com.socialcoisa.rede.blog.application;

import java.time.Instant;

import com.socialcoisa.rede.blog.domain.BlogPost;
import com.socialcoisa.rede.blog.domain.BlogPostFormat;

/**
 * Um post do blog como o front precisa: `body` e o que o autor escreveu — HTML ja sanitizado
 * (bodyFormat HTML) ou markdown simples (bodyFormat MARKDOWN), que o front renderiza como texto
 * formatado. O id e o mesmo do post-ponteiro que aparece no feed de quem segue o blog.
 */
public record BlogPostView(String id, String title, String body, BlogPostFormat bodyFormat,
		java.util.List<String> mediaIds, Instant publishedAt, Instant updatedAt) {

	public static BlogPostView from(BlogPost post) {
		return new BlogPostView(post.id(), post.title(), post.body(), post.bodyFormat(), post.mediaIds(),
			post.publishedAt(), post.updatedAt());
	}
}
