package com.socialcoisa.rede.emotes.adapters.out.providers;

import java.util.List;

import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

import tools.jackson.databind.JsonNode;

/**
 * Leitura do emoticon no formato do FrankerFaceZ — usado pela colecao global e pelo retrato popular.
 *
 * Identificador e animacao saem do proprio endereco que o FFZ publica (`/emote/{id}/...`), porque o
 * catalogo so guarda id + provedor e monta a URL depois (ver `EmoteImages`). Figurinha oculta,
 * modificadora (que tinge outra) e sem nome ficam de fora: nao sao desenhadas sozinhas no texto.
 */
final class FfzEmoteJson {

	private FfzEmoteJson() {
	}

	static Emote toEmote(JsonNode emoticon, Integer popularRank) {
		if (emoticon == null) {
			return null;
		}
		if (emoticon.path("hidden").asBoolean(false) || emoticon.path("modifier").asBoolean(false)) {
			return null;
		}
		String name = emoticon.path("name").asText("");
		String url = firstUrl(emoticon);
		String id = idFromUrl(url);
		if (name.isEmpty() || id == null) {
			return null;
		}
		boolean animated = !emoticon.path("animated").isMissingNode() && !emoticon.path("animated").isNull()
				&& (emoticon.path("animated").path("1").isString() || emoticon.path("animated").path("2").isString());
		try {
			return new Emote(name, EmoteProvider.FFZ, id, animated, popularRank);
		}
		catch (IllegalArgumentException invalid) {
			return null;
		}
	}

	private static String firstUrl(JsonNode emoticon) {
		JsonNode urls = emoticon.path("urls");
		for (String scale : List.of("2", "1", "4", "3")) {
			String url = urls.path(scale).asText("");
			if (!url.isEmpty()) {
				return url;
			}
		}
		return "";
	}

	/** `https://cdn.frankerfacez.com/emote/128054/2` → `128054`. */
	static String idFromUrl(String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		String[] parts = url.split("/");
		for (int i = parts.length - 2; i >= 0; i--) {
			if ("emote".equals(parts[i])) {
				return parts[i + 1];
			}
		}
		return null;
	}
}