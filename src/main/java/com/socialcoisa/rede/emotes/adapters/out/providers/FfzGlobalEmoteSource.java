package com.socialcoisa.rede.emotes.adapters.out.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.emotes.application.port.out.LiveEmoteSource;
import com.socialcoisa.rede.emotes.domain.Emote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Colecao GLOBAL do FrankerFaceZ (`/v1/set/global`).
 *
 * Entram so os sets de `default_sets`: os outros sets da mesma resposta sao colecoes de canal que
 * aparecem ali por conveniencia da API, nao a figurinha global do site. Sao 13 figurinhas.
 */
public class FfzGlobalEmoteSource implements LiveEmoteSource {

	private static final String URL = "https://api.frankerfacez.com/v1/set/global";

	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public FfzGlobalEmoteSource(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "FFZ-GLOBAL";
	}

	@Override
	public List<Emote> load() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
			.timeout(Duration.ofSeconds(12))
			.header("User-Agent", "enois/1.0 (+https://enois.ia.br)")
			.GET()
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		// O FFZ responde 522 quando o Cloudflare dele desiste; o catalogo antigo continua valendo.
		if (response.statusCode() != 200) {
			throw new IllegalStateException("FFZ respondeu " + response.statusCode());
		}
		return parse(objectMapper.readTree(response.body()));
	}

	static List<Emote> parse(JsonNode body) {
		List<Emote> emotes = new ArrayList<>();
		JsonNode sets = body.path("sets");
		JsonNode defaultSets = body.path("default_sets");
		List<String> ids = new ArrayList<>();
		if (defaultSets.isArray() && !defaultSets.isEmpty()) {
			for (JsonNode id : defaultSets) {
				ids.add(String.valueOf(id.asInt()));
			}
		}
		else {
			for (String id : sets.propertyNames()) {
				ids.add(id);
			}
		}
		for (String id : ids) {
			for (JsonNode emoticon : sets.path(id).path("emoticons")) {
				Emote emote = FfzEmoteJson.toEmote(emoticon, null);
				if (emote != null) {
					emotes.add(emote);
				}
			}
		}
		return emotes;
	}
}