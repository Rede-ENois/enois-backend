package com.socialcoisa.rede.shared.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy;
import com.socialcoisa.rede.events.application.EventService;
import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.shared.security.SuspiciousText;

/**
 * O contrato do cache de leitura: vale pelo TTL, renova sob demanda e nunca guarda o que nao
 * commitou.
 *
 * <p>Cada teste usa o {@link EventService} real (com o repositorio dublado) para provar que a
 * anotacao {@code @Cacheable} esta ligada de verdade: o que se conta e chamada ao repositorio, nao
 * presenca de anotacao.</p>
 */
class IntelligentCacheTests {

	/** TTL curto para o teste nao esperar 20 segundos; o comportamento e o mesmo. */
	private static final Duration TTL = Duration.ofMillis(400);

	private EventRepository repository;
	private AnnotationConfigApplicationContext context;
	private EventService events;

	@Configuration(proxyBeanMethods = false)
	@EnableCaching
	static class TestCacheConfig {

		@Bean
		CacheManager cacheManager() {
			SimpleCacheManager manager = new SimpleCacheManager();
			manager.setCaches(List.of(cache(CacheConfig.EVENTS, TTL), cache(CacheConfig.PUBLIC_PROFILES, TTL)));
			return manager;
		}

		private static CaffeineCache cache(String name, Duration ttl) {
			return new CaffeineCache(name,
				Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100).recordStats().build());
		}
	}

	@BeforeEach
	void setUp() {
		repository = mock(EventRepository.class);
		when(repository.findAll(any(), any(), any(), any(), anyBoolean(), anyInt())).thenReturn(List.of(event()));

		com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctions =
			mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class);
		when(sanctions.banned(any())).thenReturn(false);

		context = new AnnotationConfigApplicationContext();
		context.register(TestCacheConfig.class);
		context.registerBean(EventRepository.class, () -> repository);
		context.registerBean(CacheInvalidator.class, () -> new CacheInvalidator(context.getBean(CacheManager.class)));
		context.registerBean(EventService.class,
			() -> new EventService(repository, sanctions, new SuspiciousText("probe"),
				Clock.fixed(Instant.now(), ZoneOffset.UTC), context.getBean(CacheInvalidator.class)));
		context.refresh();
		events = context.getBean(EventService.class);
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	private static Event event() {
		// Daqui a uma semana: a vitrine so mostra o que ainda vai acontecer.
		String date = java.time.LocalDate.now().plusDays(7).toString();
		return Event.create("event_cache", "Meetup", date, "19:00", "ONLINE", "Dona", null, null, null,
			"dona", Instant.now());
	}

	private EventService.EventPage list() {
		return events.list(null, null, null, null, 20, false, false);
	}

	@Test
	void firstRequestQueriesTheSourceAndTheNextOnesReuseTheAnswer() {
		list();
		list();
		list();

		verify(repository, times(1)).findAll(any(), any(), any(), any(), anyBoolean(), anyInt());
	}

	@Test
	void theEntryExpiringDoesNotQueryAnythingOnItsOwn() throws Exception {
		list();
		clearInvocations(repository);

		Thread.sleep(TTL.plus(Duration.ofMillis(200)).toMillis());

		// Ninguem pediu: o vencimento so marcou a entrada, nenhuma varredura foi disparada.
		verifyNoMoreInteractions(repository);
	}

	@Test
	void theFirstRequestAfterExpiryRenewsTheEntry() throws Exception {
		list();
		Thread.sleep(TTL.plus(Duration.ofMillis(200)).toMillis());

		list();

		verify(repository, times(2)).findAll(any(), any(), any(), any(), anyBoolean(), anyInt());
	}

	@Test
	void aWriteClearsTheListSoTheChangeShowsUpOnTheNextRequest() {
		Event existing = event();
		when(repository.findById(existing.id())).thenReturn(Optional.of(existing));
		list();

		events.delete(existing.id(), "dona", false);
		list();

		verify(repository, times(2)).findAll(any(), any(), any(), any(), anyBoolean(), anyInt());
	}

	@Test
	void aFailedWriteLeavesTheCacheAlone() {
		list();
		// Nao e o dono: a exclusao recusa antes de gravar, e a vitrine continua servida do cache.
		when(repository.findById("event_cache")).thenReturn(Optional.of(event()));

		assertThat(eventWasRefused()).isTrue();
		list();

		verify(repository, times(1)).findAll(any(), any(), any(), any(), anyBoolean(), anyInt());
	}

	private boolean eventWasRefused() {
		try {
			events.delete("event_cache", "outra_pessoa", false);
			return false;
		}
		catch (RuntimeException refused) {
			return true;
		}
	}

	@Test
	void requestsArrivingTogetherWithAnExpiredEntryQueryOnce() throws Exception {
		list();
		context.getBean(CacheManager.class).getCache(CacheConfig.EVENTS).clear();

		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		List<Throwable> failures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			pool.execute(() -> {
				try {
					start.await();
					list();
				}
				catch (Throwable failure) {
					synchronized (failures) {
						failures.add(failure);
					}
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(failures).isEmpty();
		// `sync = true`: as oito requisicoes disputando a mesma chave vencida produzem UMA consulta
		// a mais (a da renovacao), nao oito.
		verify(repository, times(2)).findAll(any(), any(), any(), any(), anyBoolean(), anyInt());
	}

	private static Duration ttlOf(CaffeineCache cache) {
		Policy.FixedExpiration<?, ?> expiration =
			(Policy.FixedExpiration<?, ?>) cache.getNativeCache().policy().expireAfterWrite().orElseThrow();
		return expiration.getExpiresAfter();
	}

	@Test
	void theCacheConfigDeclaresEveryCacheWithAFreshnessPolicy() {
		// Fora do contexto do Spring o SimpleCacheManager precisa da inicializacao explicita.
		SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager(new CacheProperties());
		manager.afterPropertiesSet();

		assertThat(manager.getCacheNames()).contains(CacheConfig.PUBLIC_PROFILES, CacheConfig.PUBLIC_RANKING,
			CacheConfig.COMMUNITIES, CacheConfig.EVENTS, CacheConfig.JOBS, CacheConfig.COMMUNITY_STATS,
			CacheConfig.EXTERNAL_JOBS, CacheConfig.PAGE_PREVIEW_CARDS, CacheConfig.GIF_SEARCH,
			CacheConfig.GITHUB_REPOSITORIES, CacheConfig.GITHUB_README);

		CaffeineCache eventsCache = (CaffeineCache) manager.getCache(CacheConfig.EVENTS);
		assertThat(ttlOf(eventsCache)).isEqualTo(CacheProperties.defaultDynamicTtl());
		// Cada categoria pode ter o seu numero; o contador da landing nao segue os 20 s das vitrines.
		assertThat(ttlOf((CaffeineCache) manager.getCache(CacheConfig.COMMUNITY_STATS)))
			.isEqualTo(Duration.ofMinutes(10));

		// Recurso que ninguem abriu nao acumulou consulta nem entrada.
		assertThat(eventsCache.getNativeCache().stats().requestCount()).isZero();
	}
}
