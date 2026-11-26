package com.socialcoisa.rede.portfolio.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O título de uma página de vitrine, como texto.
 *
 * <p>O HTML é escrito pelo próprio dono e passa pela limpeza do {@code SafeHtml} na gravação, mas
 * continua sendo HTML: a lista pública precisa do primeiro {@code h1} (ou, sem ele, do primeiro
 * {@code h2}) sem tags e sem entidades. É a mesma ideia do resumo que o front monta, e só o
 * título de abertura — o resto da página não interessa a uma lista.</p>
 */
final class PortfolioTitles {

	static final int MAX_LENGTH = 120;

	private static final Pattern H1 = Pattern.compile("<h1\\b[^>]*>([\\s\\S]*?)</h1>",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern H2 = Pattern.compile("<h2\\b[^>]*>([\\s\\S]*?)</h2>",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	private static final Pattern NOT_TEXT = Pattern
		.compile("<(style|script|template)\\b[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);

	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** Entidades comuns do HTML; a decodificação é uma passada só, para `&amp;lt;` não virar tag. */
	private static final Pattern ENTITY = Pattern
		.compile("&(nbsp|#160|amp|lt|gt|quot|#39);", Pattern.CASE_INSENSITIVE);

	/** O título da página; {@code null} quando ela não tem um. */
	static String titleOf(String html) {
		if (html == null || html.isBlank()) {
			return null;
		}
		Matcher h1 = H1.matcher(html);
		if (h1.find()) {
			String text = textOf(h1.group(1));
			return text == null ? null : truncate(text);
		}
		Matcher h2 = H2.matcher(html);
		if (!h2.find()) {
			return null;
		}
		String text = textOf(h2.group(1));
		return text == null ? null : truncate(text);
	}

	private static String textOf(String fragment) {
		String text = SPACES.matcher(TAGS.matcher(NOT_TEXT.matcher(fragment).replaceAll(" ")).replaceAll(" "))
			.replaceAll(" ")
			.trim();
		if (text.isEmpty()) {
			return null;
		}
		return ENTITY.matcher(text).replaceAll(PortfolioTitles::entityText);
	}

	private static String entityText(java.util.regex.MatchResult match) {
		return switch (match.group(1).toLowerCase(java.util.Locale.ROOT)) {
			case "nbsp", "#160" -> " ";
			case "amp" -> "&";
			case "lt" -> "<";
			case "gt" -> ">";
			case "quot" -> "\"";
			default -> "'";
		};
	}

	private static String truncate(String text) {
		String trimmed = SPACES.matcher(text).replaceAll(" ").trim();
		if (trimmed.length() <= MAX_LENGTH) {
			return trimmed;
		}
		String cut = trimmed.substring(0, MAX_LENGTH - 1).stripTrailing();
		return cut + "…";
	}

	private PortfolioTitles() {
	}
}