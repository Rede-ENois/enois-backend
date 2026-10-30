package com.socialcoisa.rede.emotes.adapters;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.emotes.adapters.out.images.EmoteImageCache;
import com.socialcoisa.rede.emotes.adapters.out.images.HttpImageFetcher;
import com.socialcoisa.rede.emotes.adapters.out.images.ImageFetcher;
import com.socialcoisa.rede.emotes.adapters.out.providers.BttvEmoteSource;
import com.socialcoisa.rede.emotes.adapters.out.providers.EmbeddedCatalogSource;
import com.socialcoisa.rede.emotes.adapters.out.providers.FfzGlobalEmoteSource;
import com.socialcoisa.rede.emotes.adapters.out.providers.FfzListingEmoteSource;
import com.socialcoisa.rede.emotes.application.EmoteCatalogService;
import com.socialcoisa.rede.emotes.application.port.out.EmoteSource;
import com.socialcoisa.rede.emotes.application.port.out.LiveEmoteSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Fontes e cache de imagem do catalogo de figurinhas.
 *
 * A ORDEM das fontes e a prioridade da arte quando o mesmo codigo existe em mais de uma: BTTV antes
 * do FFZ, como o seletor sempre mostrou. A listagem de ANIMADAS vem antes da popular de proposito —
 * o FFZ tem varios envios com o mesmo nome, e o que interessa desenhar no texto e a versao em
 * movimento; a popular entra depois so para dar a posicao de uso. O arquivo embarcado e lido a cada
 * montagem, para preencher o que as fontes ao vivo nao trouxeram.
 */
@Configuration(proxyBeanMethods = false)
class EmotesConfig {

	@Bean
	LiveEmoteSources liveEmoteSources(ObjectMapper objectMapper,
			@Value("${rede.emotes.popular-pages:3}") int popularPages,
			@Value("${rede.emotes.animated-pages:2}") int animatedPages) {
		List<LiveEmoteSource> sources = List.of(new BttvEmoteSource(objectMapper),
				new FfzGlobalEmoteSource(objectMapper), new FfzListingEmoteSource(objectMapper, animatedPages, true),
				new FfzListingEmoteSource(objectMapper, popularPages, false));
		return new LiveEmoteSources(sources);
	}

	@Bean
	EmbeddedCatalogSource embeddedCatalogSource(ObjectMapper objectMapper) {
		return new EmbeddedCatalogSource(objectMapper);
	}

	@Bean
	EmoteCatalogService emoteCatalogService(LiveEmoteSources liveEmoteSources, EmoteSource embeddedCatalogSource) {
		return new EmoteCatalogService(liveEmoteSources.sources(), embeddedCatalogSource);
	}

	@Bean
	ImageFetcher imageFetcher() {
		return new HttpImageFetcher();
	}

	@Bean
	EmoteImageCache emoteImageCache(ImageFetcher imageFetcher) {
		return new EmoteImageCache(imageFetcher);
	}

	/** As fontes ao vivo na ordem de prioridade. */
	record LiveEmoteSources(List<LiveEmoteSource> sources) {
	}

}