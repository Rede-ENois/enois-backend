package com.socialcoisa.rede.blog.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.util.StringUtils;

import com.socialcoisa.rede.blog.application.BlogNewsletterService;
import com.socialcoisa.rede.blog.application.BlogPostView;
import com.socialcoisa.rede.blog.application.BlogView;

/**
 * Feed Atom 1.0 de um blog: titulo, link, data e um resumo em TEXTO PURO de cada post. O corpo
 * completo fica de fora de proposito — ele pode ser HTML de tema proprio e o leitor de RSS e um
 * cliente que nao controlamos; quem quer ler tudo segue o link para o blog.
 */
final class BlogAtomFeed {

	static final int MAX_ENTRIES = 20;
	static final String CONTENT_TYPE = "application/atom+xml;charset=UTF-8";

	private BlogAtomFeed() {
	}

	/**
	 * @param frontendUrl endereco publico do app, sem barra final (os links do feed apontam para ele)
	 * @param now usado so quando o blog ainda nao tem post: Atom exige `updated` no feed
	 */
	static String render(BlogView blog, String frontendUrl, Instant now) {
		String blogLink = frontendUrl + "/blog/" + blog.slug();
		String selfLink = frontendUrl + "/api/blogs/" + blog.slug() + "/feed.xml";
		List<BlogPostView> items = blog.items().stream().limit(MAX_ENTRIES).toList();
		Instant updated = items.stream().map(BlogAtomFeed::lastChange).max(Instant::compareTo).orElse(now);

		StringBuilder xml = new StringBuilder(2048);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"pt-BR\">\n");
		xml.append("  <id>").append(escape(blogLink)).append("</id>\n");
		xml.append("  <title>").append(escape(blog.title())).append("</title>\n");
		if (StringUtils.hasText(blog.description())) {
			xml.append("  <subtitle>").append(escape(blog.description())).append("</subtitle>\n");
		}
		xml.append("  <link rel=\"alternate\" type=\"text/html\" href=\"").append(escape(blogLink)).append("\"/>\n");
		xml.append("  <link rel=\"self\" type=\"application/atom+xml\" href=\"").append(escape(selfLink)).append("\"/>\n");
		xml.append("  <updated>").append(updated).append("</updated>\n");
		xml.append("  <author><name>").append(escape(blog.authorName())).append("</name></author>\n");
		for (BlogPostView post : items) {
			String link = blogLink + "/" + post.id();
			xml.append("  <entry>\n");
			xml.append("    <id>").append(escape(link)).append("</id>\n");
			xml.append("    <title>").append(escape(post.title())).append("</title>\n");
			xml.append("    <link rel=\"alternate\" type=\"text/html\" href=\"").append(escape(link)).append("\"/>\n");
			xml.append("    <published>").append(post.publishedAt()).append("</published>\n");
			xml.append("    <updated>").append(lastChange(post)).append("</updated>\n");
			String summary = BlogNewsletterService.previewOf(post.body());
			if (!summary.isEmpty()) {
				xml.append("    <summary type=\"text\">").append(escape(summary)).append("</summary>\n");
			}
			xml.append("  </entry>\n");
		}
		xml.append("</feed>\n");
		return xml.toString();
	}

	private static Instant lastChange(BlogPostView post) {
		return post.updatedAt() != null ? post.updatedAt() : post.publishedAt();
	}

	/** Escapa o texto para XML e descarta os caracteres que o XML 1.0 nao admite nem escapados. */
	static String escape(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		text.codePoints().forEach(codePoint -> {
			switch (codePoint) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&apos;");
				default -> {
					if (isXmlChar(codePoint)) {
						out.appendCodePoint(codePoint);
					}
				}
			}
		});
		return out.toString();
	}

	private static boolean isXmlChar(int c) {
		return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
			|| (c >= 0x10000 && c <= 0x10FFFF);
	}
}
