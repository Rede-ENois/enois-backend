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
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Colecao GLOBAL do BetterTTV (`/3/cached/emotes/global`).
 *
 * E a lista COMPLETA que o BTTV publica sem chave: 65 figurinhas, resposta em ~0,3s. O que a pagina
 * `betterttv.com/emotes/popular` mostra nao esta disponivel fora do dominio deles (responde 403 para
 * qualquer `Origin`/`Referer` que nao seja o proprio site), entao nao entra aqui.
 */
public class BttvEmoteSource implements LiveEmoteSource {

	private static final String URL = "https://api.betterttv.net/3/cached/emotes/global";

	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public BttvEmoteSource(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "BTTV";
	}

	@Override
	public List<Emote> load() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "enois/1.0 (+https://enois.ia.br)")
			.GET()
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("BTTV respondeu " + response.statusCode());
		}
		return parse(objectMapper.readTree(response.body()));
	}

	/** Separado para o teste cobrir o formato da resposta sem rede. */
	static List<Emote> parse(JsonNode body) {
		List<Emote> emotes = new ArrayList<>();
		if (!body.isArray()) {
			return emotes;
		}
		for (JsonNode node : body) {
			String id = node.path("id").asText("");
			String code = node.path("code").asText("");
			boolean animated = node.path("animated").asBoolean(false) || "gif".equals(node.path("imageType").asText(""));
			add(emotes, code, id, animated);
		}
		return emotes;
	}

	private static void add(List<Emote> emotes, String code, String id, boolean animated) {
		try {
			emotes.add(new Emote(code, EmoteProvider.BTTV, id, animated, null));
		}
		catch (IllegalArgumentException ignored) {
			// Figurinha com codigo fora do padrao nunca seria usada no texto: fica de fora do catalogo.
		}
	}
}