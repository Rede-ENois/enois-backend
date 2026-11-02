package com.socialcoisa.rede.emotes.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socialcoisa.rede.emotes.application.port.out.EmoteSource;
import com.socialcoisa.rede.emotes.application.port.out.LiveEmoteSource;
import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteCatalog;
import com.socialcoisa.rede.emotes.domain.EmoteNamePolicy;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

/**
 * O catalogo de figurinhas do site, montado no BACKEND e guardado em memoria.
 *
 * Por que aqui e nao no navegador: o catalogo antigo era montado a cada visita com tres chamadas a
 * provedores de terceiro. O `api.frankerfacez.com` chegou a responder 522 depois de 19,9s, e o
 * navegador ficava em "Carregando figurinhas..." sem limite — com o seletor abrindo de uma vez so,
 * depois da espera, com 737 figurinhas de uma vez. Aqui a montagem acontece em segundo plano, o
 * leitor recebe uma pagina por vez e o backend responde de memoria.
 *
 * Regras da juncao:
 * - arte ao vivo manda (a colecao global do BTTV e do FFZ e a fonte viva do provedor);
 * - o codigo repetido fica com o BTTV, como sempre foi no seletor;
 * - a mesma figurinha vinda de duas listagens do FFZ (popular e animadas) entra uma vez, com a
 *   posicao popular de uma e a marcacao de animada da outra;
 * - fonte que falha nao apaga o catalogo: o que ja estava montado continua servindo.
 */
/**
 * Bean criado em `EmotesConfig` (as fontes ao vivo sao montadas la, na ordem de prioridade).
 */
public class EmoteCatalogService {

	private static final Logger LOG = LoggerFactory.getLogger(EmoteCatalogService.class);

	private final List<LiveEmoteSource> liveSources;
	private final EmoteSource embedded;

	private final Map<String, List<Emote>> lastGood = new ConcurrentHashMap<>();

	private volatile EmoteCatalog catalog;
	private volatile Set<String> artsAnimated = Set.of();
	private volatile Set<String> artsBarradas = Set.of();
	private volatile boolean liveLoaded;

	public EmoteCatalogService(List<LiveEmoteSource> liveSources, EmoteSource embedded) {
		this.liveSources = List.copyOf(liveSources);
		this.embedded = embedded;
		// No boot o catalogo ja nasce com o retrato embarcado: nenhuma requisicao encontra seletor vazio.
		Barreira barreira = new Barreira();
		List<Emote> embarcado = barreira.permitidas(loadEmbedded());
		this.catalog = embarcado.isEmpty() ? EmoteCatalog.empty() : new EmoteCatalog(sort(embarcado), Instant.now());
		this.artsAnimated = animatedArts(this.catalog.emotes());
		this.artsBarradas = barreira.artes();
	}

	public EmoteCatalog catalog() {
		return catalog;
	}

	/** `true` quando pelo menos um provedor ao vivo `respondeu` desde o ultimo boot. */
	public boolean liveLoaded() {
		return liveLoaded;
	}

	/** `true` quando o catalogo conhece esta figurinha como animada (o proxy pede o arquivo em movimento). */
	public boolean isAnimated(String providerParam, String id) {
		EmoteProvider provider = EmoteProvider.parse(providerParam);
		return provider != null && id != null && artsAnimated.contains(arte(provider, id));
	}

	/**
	 * `true` quando esta arte esta BARRADA. O proxy de imagem usa isto como porta: figurinha de nome
	 * barrado nunca e baixada do CDN de terceiro, nem quando alguem pede a imagem pelo id direto.
	 *
	 * A porta e so sobre a arte barrada, e nao sobre "o que esta no catalogo": no boot o catalogo tem
	 * apenas o retrato embarcado, e travar tudo que ainda nao chegou daria 404 em figurinha legitima
	 * que ja esta em post antigo.
	 */
	public boolean isBlocked(String providerParam, String id) {
		EmoteProvider provider = EmoteProvider.parse(providerParam);
		return provider != null && id != null && artsBarradas.contains(arte(provider, id));
	}

	public EmotePage page(int page, int limit, String query, String provider) {
		return EmotePage.of(EmotePage.filter(catalog.emotes(), query, provider), page, limit);
	}

	public List<Emote> index() {
		return catalog.emotes();
	}

	/**
	 * Atualiza o catalogo. Chamado pelo agendador; roda fora do caminho da requisicao porque as
	 * listagens do FFZ levam alguns segundos cada.
	 *
	 * Cada fonte guarda a ULTIMA resposta boa: fonte que falha nao apaga o que ela ja trouxe, e o
	 * catalogo so cresce. E o que o pedido do produto descreve — de 3 em 3 horas tenta somar mais
	 * figurinha, e "se deu erro em alguma, nao substitui, vida que segue".
	 */
	public synchronized void refresh() {
		for (LiveEmoteSource fonte : liveSources) {
			try {
				List<Emote> carregadas = fonte.load();
				lastGood.put(fonte.name(), List.copyOf(carregadas));
			}
			catch (Exception falha) {
				LOG.warn("Fonte de figurinhas {} falhou; mantendo a ultima copia ({} figurinhas): {}", fonte.name(),
						lastGood.getOrDefault(fonte.name(), List.of()).size(), falha.toString());
			}
		}
		rebuild();
	}

	/** Junta a ultima copia de cada fonte com o retrato embarcado, sem repetir figurinha nem arte. */
	private void rebuild() {
		Map<String, Emote> porCodigo = new LinkedHashMap<>();
		Map<String, Integer> posicoes = new HashMap<>();
		Set<String> artes = new HashSet<>();
		Barreira barreira = new Barreira();

		List<List<Emote>> rodadas = new ArrayList<>();
		boolean algumAoVivo = false;
		for (LiveEmoteSource fonte : liveSources) {
			List<Emote> carregadas = lastGood.getOrDefault(fonte.name(), List.of());
			algumAoVivo = algumAoVivo || !carregadas.isEmpty();
			rodadas.add(carregadas);
		}
		rodadas.add(loadEmbedded());

		for (List<Emote> rodada : rodadas) {
			// Nome barrado e descartado aqui, na entrada: o que nao entra no catalogo nunca e baixado.
			for (Emote emote : barreira.permitidas(rodada)) {
				Emote existente = porCodigo.get(emote.code());
				if (existente != null) {
					// Mesma figurinha em outra rodada: a listagem popular traz a posicao, a listagem de
					// animadas traz o "tem versao em movimento" (e ela nao publica posicao).
					if (existente.provider() == emote.provider() && existente.id().equals(emote.id())) {
						porCodigo.put(emote.code(), merge(existente, emote));
					}
					if (emote.popular()) {
						posicoes.putIfAbsent(emote.code(), emote.popularRank());
					}
					continue;
				}
				// Mesma arte em dois codigos: entra uma vez, com o codigo de quem veio primeiro.
				if (!artes.add(arte(emote.provider(), emote.id()))) {
					continue;
				}
				porCodigo.put(emote.code(), emote);
				if (emote.popular()) {
					posicoes.putIfAbsent(emote.code(), emote.popularRank());
				}
			}
		}

		if (porCodigo.isEmpty()) {
			LOG.warn("Nenhuma fonte de figurinhas respondeu; o catalogo continua vazio.");
			return;
		}
		List<Emote> montado = new ArrayList<>(porCodigo.size());
		for (Emote emote : porCodigo.values()) {
			Integer posicao = posicoes.get(emote.code());
			montado.add(posicao == null ? emote : emote.popularAt(posicao));
		}
		catalog = new EmoteCatalog(sort(montado), Instant.now());
		artsAnimated = animatedArts(catalog.emotes());
		artsBarradas = barreira.artes();
		liveLoaded = liveLoaded || algumAoVivo;
		LOG.info("Catalogo de figurinhas montado: {} (populares {}, animadas {}, barradas {})", catalog.emotes().size(),
				catalog.emotes().stream().filter(Emote::popular).count(), artsAnimated.size(), artsBarradas.size());
	}

	/**
	 * Aplica a politica de nome a uma rodada e guarda a arte de quem caiu.
	 *
	 * A arte barrada e guardada justamente para o proxy de imagem poder recusar a figurinha mesmo
	 * quando pedida pelo id direto — sem isso, o nome sairia do seletor e a imagem continuaria sendo
	 * buscada no CDN de terceiro por quem tivesse o endereco.
	 */
	private static final class Barreira {

		private final Set<String> artes = new HashSet<>();

		List<Emote> permitidas(List<Emote> emotes) {
			List<Emote> permitidas = new ArrayList<>(emotes.size());
			for (Emote emote : emotes) {
				if (EmoteNamePolicy.blocked(emote.code())) {
					artes.add(arte(emote.provider(), emote.id()));
					continue;
				}
				permitidas.add(emote);
			}
			return permitidas;
		}

		Set<String> artes() {
			return Set.copyOf(artes);
		}

	}

	/** A figurinha que veio primeiro manda na arte e no codigo; a segunda so acrescenta a animacao. */
	private static Emote merge(Emote existente, Emote novo) {
		return new Emote(existente.code(), existente.provider(), existente.id(),
				existente.animated() || novo.animated(), existente.popularRank());
	}

	private static String arte(EmoteProvider provider, String id) {
		return provider.name() + "/" + id;
	}

	private static Set<String> animatedArts(List<Emote> emotes) {
		Set<String> arts = new HashSet<>();
		for (Emote emote : emotes) {
			if (emote.animated()) {
				arts.add(arte(emote.provider(), emote.id()));
			}
		}
		return Set.copyOf(arts);
	}

	private List<Emote> loadEmbedded() {
		try {
			return embedded.load();
		}
		catch (Exception falha) {
			LOG.error("Retrato embarcado das figurinhas nao pode ser lido: {}", falha.toString());
			return List.of();
		}
	}

	private static List<Emote> sort(List<Emote> emotes) {
		List<Emote> copia = new ArrayList<>(emotes);
		copia.sort(EmotePage.ORDEM_DO_SELETOR);
		return copia;
	}
}