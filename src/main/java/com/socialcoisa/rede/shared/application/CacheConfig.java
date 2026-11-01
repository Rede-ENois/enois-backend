package com.socialcoisa.rede.shared.application;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache em memoria do processo.
 *
 * <p>Serve para leituras compartilhadas que custam caro (contadores, agregados, vitrines e a
 * projecao publica de perfil) e para nao repetir a mesma consulta a cada abertura de tela. E de
 * proposito em memoria (nao Redis): cada pod tem o seu proprio cache, o dado e recalculavel e
 * assim nao entra dependencia de infra nova. A contrapartida esta documentada em
 * {@link CacheInvalidator}: invalidar aqui nao invalida os outros pods, quem garante o limite e o
 * TTL.</p>
 *
 * <p><b>Nada aqui consulta o banco por conta propria.</b> {@code expireAfterWrite} so marca a
 * entrada como vencida; a proxima requisicao que precisar do dado paga a consulta e repoe o cache.
 * Recurso que ninguem abre nao gera consulta nenhuma. {@code @Cacheable(sync = true)} fecha a
 * avalanche quando varias requisicoes chegam com a entrada vencida: uma consulta, as outras
 * esperam o resultado dela.</p>
 *
 * <p>A lista de caches e fechada: cache sem TTL e sem dono conhecido vira fonte de bug. Cada um
 * declara o seu frescor em {@link CacheProperties} ({@code rede.cache.*}).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

	/** Projecao publica de um perfil (`GET /users/{username}` e `/users/by-id/{id}`). */
	public static final String PUBLIC_PROFILES = "publicProfiles";

	/** Vitrine "quem esta chegando agora" (`GET /ranking`). */
	public static final String PUBLIC_RANKING = "publicRanking";

	/** Vitrine de comunidades. */
	public static final String COMMUNITIES = "communitiesList";

	/** Vitrine de eventos. */
	public static final String EVENTS = "eventsList";

	/** Vitrine de vagas internas. */
	public static final String JOBS = "jobsList";

	public static final String COMMUNITY_STATS = "communityStats";

	public static final String EXTERNAL_JOBS = "externalJobs";

	public static final String PAGE_PREVIEW_CARDS = "pagePreviewCards";

	public static final String GIF_SEARCH = "gifSearch";

	public static final String GITHUB_REPOSITORIES = "githubRepositories";

	public static final String GITHUB_README = "githubReadme";

	@Bean
	CacheManager cacheManager(CacheProperties properties) {
		SimpleCacheManager manager = new SimpleCacheManager();
		manager.setCaches(List.of(cache(PUBLIC_PROFILES, properties.getPublicProfiles()),
			cache(PUBLIC_RANKING, properties.getPublicRanking()), cache(COMMUNITIES, properties.getCommunities()),
			cache(EVENTS, properties.getEvents()), cache(JOBS, properties.getJobs()),
			cache(COMMUNITY_STATS, properties.getCommunityStats()),
			cache(EXTERNAL_JOBS, properties.getExternalJobs()),
			cache(PAGE_PREVIEW_CARDS, properties.getPagePreviewCards()),
			cache(GIF_SEARCH, properties.getGifSearch()),
			cache(GITHUB_REPOSITORIES, properties.getGithubRepositories()),
			cache(GITHUB_README, properties.getGithubReadme())));
		return manager;
	}

	private CaffeineCache cache(String name, CacheProperties.Category category) {
		return new CaffeineCache(name, Caffeine.newBuilder()
			// Vencimento sob demanda: marca, nao busca. Nenhum scheduler toca no banco.
			.expireAfterWrite(category.getTtl())
			.maximumSize(category.getMaxEntries())
			// Acertos e erros por cache; os testes de comportamento leem daqui.
			.recordStats()
			.build());
	}
}
