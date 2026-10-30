package com.socialcoisa.rede.emotes.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.socialcoisa.rede.emotes.domain.Emote;

/**
 * Uma pagina do catalogo, ja filtrada pelo seletor.
 *
 * `total` e quantas figurinhas passam no filtro (nao o tamanho do catalogo) — e o numero que o
 * seletor mostra como "737 figurinhas" enquanto carrega 40 por vez.
 */
public record EmotePage(List<Emote> items, int page, int limit, int total, int totalPages) {

	/** Populares por uso primeiro; o resto em ordem alfabetica. */
	public static final Comparator<Emote> ORDEM_DO_SELETOR = Comparator
		.comparingInt((Emote emote) -> emote.popular() ? 0 : 1)
		.thenComparingInt((Emote emote) -> emote.popular() ? emote.popularRank() : Integer.MAX_VALUE)
		.thenComparing(Emote::code, String.CASE_INSENSITIVE_ORDER);

	public static EmotePage of(List<Emote> filtrados, int page, int limit) {
		int total = filtrados.size();
		int totalPages = Math.max(1, (total + limit - 1) / limit);
		int atual = Math.clamp(page, 1, totalPages);
		int inicio = (atual - 1) * limit;
		List<Emote> items = filtrados.subList(inicio, Math.min(total, inicio + limit));
		return new EmotePage(List.copyOf(items), atual, limit, total, totalPages);
	}

	/**
	 * Aplica busca e provedor.
	 *
	 * A ordem e a do seletor: `POPULAR` pela posicao de uso; `ALL` com os populares primeiro e o
	 * resto em ordem alfabetica; `BTTV`/`FFZ` so a colecao do provedor, em ordem alfabetica.
	 */
	public static List<Emote> filter(List<Emote> catalogo, String query, String provider) {
		String termo = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		String filtro = provider == null ? "ALL" : provider.trim().toUpperCase(Locale.ROOT);

		List<Emote> filtrados = new ArrayList<>();
		for (Emote emote : catalogo) {
			if (!casaProvedor(emote, filtro)) {
				continue;
			}
			if (!termo.isEmpty() && !emote.code().toLowerCase(Locale.ROOT).contains(termo)) {
				continue;
			}
			filtrados.add(emote);
		}

		if ("BTTV".equals(filtro) || "FFZ".equals(filtro)) {
			filtrados.sort(Comparator.comparing(Emote::code, String.CASE_INSENSITIVE_ORDER));
		}
		else {
			filtrados.sort(ORDEM_DO_SELETOR);
		}
		return filtrados;
	}

	private static boolean casaProvedor(Emote emote, String filtro) {
		return switch (filtro) {
			case "POPULAR" -> emote.popular();
			case "BTTV", "FFZ" -> emote.provider().name().equals(filtro);
			default -> true;
		};
	}

	/** Deduplica por codigo: o primeiro (BTTV antes de FFZ) fica com a arte. */
	static Map<String, Emote> byCode(List<Emote> emotes) {
		Map<String, Emote> byCode = new LinkedHashMap<>();
		for (Emote emote : emotes) {
			byCode.putIfAbsent(emote.code(), emote);
		}
		return byCode;
	}
}