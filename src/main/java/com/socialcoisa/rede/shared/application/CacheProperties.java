package com.socialcoisa.rede.shared.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Politica de frescor de cada cache, centralizada.
 *
 * <p>O TTL e o tempo MAXIMO de validade de uma entrada — nao um intervalo de atualizacao. Nada aqui
 * agenda consulta ao banco: a entrada expira e a proxima requisicao que precisar dela paga a
 * consulta de novo (ver {@link CacheConfig}). Por isso o padrao dos dados dinamicos e curto: 20
 * segundos de atraso no pior caso, contra uma consulta por requisicao quando ninguem mexe.</p>
 *
 * <p>Os caches dinamicos herdam o mesmo numero de `REDE_CACHE_DYNAMIC_TTL` em
 * `application.properties`; cada categoria pode sobrescrever o seu
 * (`rede.cache.public-profiles.ttl=5s`, por exemplo) sem tocar em codigo. Os valores aqui sao o
 * padrao de execucao sem `application.properties` (testes, por exemplo).</p>
 */
@ConfigurationProperties(prefix = "rede.cache")
public class CacheProperties {

	/** Um cache: quanto tempo a entrada vale e quantas entradas cabem no pod. */
	public static class Category {

		private Duration ttl;
		private int maxEntries;

		public Category(Duration ttl, int maxEntries) {
			this.ttl = ttl;
			this.maxEntries = maxEntries;
		}

		public Duration getTtl() {
			return ttl;
		}

		public void setTtl(Duration ttl) {
			this.ttl = ttl;
		}

		public int getMaxEntries() {
			return maxEntries;
		}

		public void setMaxEntries(int maxEntries) {
			this.maxEntries = maxEntries;
		}
	}

	/** Frescor padrao dos dados dinamicos: 20 segundos de atraso no pior caso. */
	public static final Duration DEFAULT_DYNAMIC_TTL = Duration.ofSeconds(20);

	public static Duration defaultDynamicTtl() {
		return DEFAULT_DYNAMIC_TTL;
	}

	/** Perfil publico por username/id: projecao publica, igual para todo mundo que abre a pagina. */
	private final Category publicProfiles = new Category(DEFAULT_DYNAMIC_TTL, 5_000);

	/** Vitrine "quem esta chegando agora" (ranking publico). */
	private final Category publicRanking = new Category(DEFAULT_DYNAMIC_TTL, 64);

	/** Vitrine de comunidades (filtros + paginacao), antes do "sou membro" de cada leitor. */
	private final Category communities = new Category(DEFAULT_DYNAMIC_TTL, 500);

	/** Vitrine de eventos (filtros + paginacao), antes do "vou participar" de cada leitor. */
	private final Category events = new Category(DEFAULT_DYNAMIC_TTL, 500);

	/** Vitrine de vagas (filtros + paginacao), antes do estado do leitor (salva/candidatada). */
	private final Category jobs = new Category(DEFAULT_DYNAMIC_TTL, 500);

	/** Contador da landing: varre sessao, post e comentario; 10 min e irrelevante para o numero. */
	private final Category communityStats = new Category(Duration.ofMinutes(10), 4);

	/** Vagas externas: so mudam na importacao (boot/meia-noite). */
	private final Category externalJobs = new Category(Duration.ofMinutes(5), 16);

	/** Cartao de compartilhamento da pagina (PNG); muda so quando a pagina e editada. */
	private final Category pagePreviewCards = new Category(Duration.ofHours(1), 16);

	/** Busca de GIF do seletor: a chave inclui o termo, entao o teto de entradas e maior. */
	private final Category gifSearch = new Category(Duration.ofMinutes(10), 200);

	/** Repositorios e README publicos do GitHub, por login (importacao para o perfil). */
	private final Category githubRepositories = new Category(Duration.ofMinutes(3), 200);

	private final Category githubReadme = new Category(Duration.ofMinutes(3), 200);

	public Category getPublicProfiles() {
		return publicProfiles;
	}

	public Category getPublicRanking() {
		return publicRanking;
	}

	public Category getCommunities() {
		return communities;
	}

	public Category getEvents() {
		return events;
	}

	public Category getJobs() {
		return jobs;
	}

	public Category getCommunityStats() {
		return communityStats;
	}

	public Category getExternalJobs() {
		return externalJobs;
	}

	public Category getPagePreviewCards() {
		return pagePreviewCards;
	}

	public Category getGifSearch() {
		return gifSearch;
	}

	public Category getGithubRepositories() {
		return githubRepositories;
	}

	public Category getGithubReadme() {
		return githubReadme;
	}
}