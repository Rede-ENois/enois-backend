package com.socialcoisa.rede.emotes.adapters.out.providers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.socialcoisa.rede.emotes.application.port.out.EmoteSource;
import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Retrato EMBARCADO no jar (`emotes/catalogo.json`).
 *
 * O catalogo ao vivo depende de tres provedores de terceiro; quando o pod sobe sem egress (ou o FFZ
 * devolve 522, o que acontece com frequencia) e o refresh agendado nao traz nada, este arquivo e o
 * que sobra. Por isso ele NAO e so a lista de populares: traz a colecao global do BTTV e do FFZ, as
 * mais usadas e as animadas — com provedor, identificador e a marcacao de animacao —, na mesma juncao
 * que `EmoteCatalogService.rebuild()` faz ao vivo. Assim o seletor nasce com figurinha comum E
 * animada mesmo com a rede toda fora.
 *
 * O arquivo e gerado por `scripts/emotes-snapshot.py`, que segue a mesma ordem de prioridade das
 * fontes (`EmotesConfig`). O refresh agendado soma por cima; nada aqui e apagado por ele.
 */
public class EmbeddedCatalogSource implements EmoteSource {

	private final ObjectMapper objectMapper;
	private final Resource resource;

	public EmbeddedCatalogSource(ObjectMapper objectMapper) {
		this(objectMapper, new ClassPathResource("emotes/catalogo.json"));
	}

	EmbeddedCatalogSource(ObjectMapper objectMapper, Resource resource) {
		this.objectMapper = objectMapper;
		this.resource = resource;
	}

	@Override
	public String name() {
		return "EMBARCADO";
	}

	@Override
	public boolean local() {
		return true;
	}

	@Override
	public List<Emote> load() throws Exception {
		try (InputStream stream = resource.getInputStream()) {
			return parse(objectMapper.readTree(stream).path("emotes"));
		}
	}

	/** Separado para o teste cobrir o formato do arquivo sem classpath. */
	static List<Emote> parse(JsonNode emotes) {
		List<Emote> result = new ArrayList<>();
		if (emotes == null || !emotes.isArray()) {
			return result;
		}
		for (JsonNode node : emotes) {
			EmoteProvider provider = EmoteProvider.parse(node.path("provider").asText(""));
			if (provider == null) {
				continue;
			}
			JsonNode rank = node.path("popularRank");
			Integer popularRank = rank.isMissingNode() || rank.isNull() ? null : rank.asInt();
			try {
				result.add(new Emote(node.path("code").asText(""), provider, node.path("id").asText(""),
						node.path("animated").asBoolean(false), popularRank));
			}
			catch (IllegalArgumentException invalid) {
				// Linha corrompida no retrato nao derruba o catalogo: as outras continuam valendo.
			}
		}
		return result;
	}

}