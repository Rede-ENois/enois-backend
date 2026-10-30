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
 * Listagem do FFZ por uso (`/v1/emotes?sort=count-desc`), em duas variantes:
 *
 * - **popular** (`animated=false`): as mais usadas do site; a posicao na lista vira a posicao
 *   popular do catalogo. E a fonte que faz o catalogo crescer sozinho.
 * - **animadas** (`animated=true`): so o recorte com versao em movimento. A listagem popular NAO
 *   publica o campo `animated` (nenhuma das 200 da primeira pagina traz a chave), entao olhar para
 *   ela e o unico jeito de saber sem tentar baixar o arquivo — e tentar o arquivo de uma figurinha
 *   estatica trava a conexao em vez de dar 404. Aqui a figurinha entra sem posicao popular: o posto
 *   dela continua sendo o da listagem geral, quando aparece la.
 *
 * So roda no refresh agendado, nunca no clique: cada pagina de 200 leva alguns segundos. Se uma
 * fonte falhar, o catalogo mantem a ultima copia dela e o retrato embarcado (`emotes/catalogo.json`)
 * continua servindo.
 */
public class FfzListingEmoteSource implements LiveEmoteSource {

	private static final String URL = "https://api.frankerfacez.com/v1/emotes";

	private final ObjectMapper objectMapper;
	private final int pages;
	private final boolean animatedOnly;
	private final String name;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public FfzListingEmoteSource(ObjectMapper objectMapper, int pages, boolean animatedOnly) {
		this.objectMapper = objectMapper;
		this.pages = Math.clamp(pages, 1, 20);
		this.animatedOnly = animatedOnly;
		this.name = animatedOnly ? "FFZ-ANIMADAS" : "FFZ-POPULAR";
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public List<Emote> load() throws Exception {
		List<Emote> emotes = new ArrayList<>();
		for (int page = 1; page <= pages; page++) {
			List<JsonNode> pagina = emoticons(fetchPage(page));
			if (pagina.isEmpty()) {
				break;
			}
			for (JsonNode emoticon : pagina) {
				// `sort=count-desc` vem na ordem de uso: a posicao aceita na lista e a posicao popular.
				Emote emote = FfzEmoteJson.toEmote(emoticon, animatedOnly ? null : emotes.size() + 1);
				if (emote != null) {
					emotes.add(emote);
				}
			}
		}
		return emotes;
	}

	/**
	 * A listagem vem embrulhada (`{"_pages":…,"_total":…,"emoticons":[…]}`), nao como lista solta.
	 * Separado para o teste pegar o formato sem rede.
	 */
	static List<JsonNode> emoticons(JsonNode body) {
		List<JsonNode> emoticons = new ArrayList<>();
		if (body == null) {
			return emoticons;
		}
		JsonNode lista = body.path("emoticons").isArray() ? body.path("emoticons") : body;
		if (lista.isArray()) {
			for (JsonNode node : lista) {
				emoticons.add(node);
			}
		}
		return emoticons;
	}

	private JsonNode fetchPage(int page) throws Exception {
		String url = URL + "?sort=count-desc&per_page=200&page=" + page + (animatedOnly ? "&animated=true" : "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(45))
			.header("User-Agent", "enois/1.0 (+https://enois.ia.br)")
			.header("Accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(name + " respondeu " + response.statusCode());
		}
		return objectMapper.readTree(response.body());
	}
}