package com.socialcoisa.rede.gifs.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.gifs.application.port.out.GifProvider;

/**
 * Busca de GIF do seletor de comentario.
 *
 * O primeiro provedor DISPONIVEL que responder fica com a busca (o Tenor vem antes do Giphy pela
 * ordem dos beans), sem misturar os dois: duas fontes numa grade so confunde, e a atribuicao exige
 * dizer de quem e o GIF. Se um provedor com chave falhar (chave revogada, cota, fora do ar), o
 * proximo tenta; so quando todos falham o erro chega a tela.
 * Com cache curto (ver CacheConfig) o termo repetido nao gasta a cota do provedor de novo.
 */
@Service
public class GifSearchService {

	private static final Logger log = LoggerFactory.getLogger(GifSearchService.class);

	private final List<GifProvider> providers;

	GifSearchService(List<GifProvider> providers) {
		this.providers = providers;
	}

	@Cacheable(cacheNames = "gifSearch", key = "#query + '|' + #limit")
	public GifSearchResult search(String query, int limit) {
		RuntimeException lastFailure = null;
		for (GifProvider provider : providers) {
			if (!provider.available()) {
				continue;
			}
			try {
				return new GifSearchResult(true, provider.name(), provider.search(query, limit));
			} catch (RuntimeException failure) {
				log.warn("gif_provider_failed provider={}", provider.name());
				lastFailure = failure;
			}
		}
		if (lastFailure != null) {
			throw lastFailure;
		}
		return GifSearchResult.unavailable();
	}
}
