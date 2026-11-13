package com.socialcoisa.rede.shared.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;

/**
 * Broadcast com <b>duas instâncias reais</b> sobre a mesma tabela de sinais.
 *
 * <p>{@code InstanceBroadcastTests} prova o consumidor: escreve a linha com {@code origin_instance}
 * alheio e confere que o poller desta instância entrega. Isso deixa de fora exatamente o lado que
 * pode quebrar sem ninguém notar — o <b>emissor</b>. Aqui a segunda instância é um objeto de
 * verdade (emissor próprio, poller próprio, cache próprio), com a única diferença que o teste cria:
 * ela não tem contexto Spring, porque o contrato entre instâncias é a tabela, não o contexto.</p>
 *
 * <p>O critério da fase 3 é literal — "`MessageStreamHub` funciona com duas instâncias; cache
 * invalida em todas". Duas instâncias aqui = dois {@code InstanceBroadcaster} com ids distintos,
 * dois pollers lendo a mesma tabela, dois caches.</p>
 */
@SpringBootTest
class TwoInstancesBroadcastTests {

	@Autowired
	private InstanceSignalStore store;

	@Autowired
	private InstanceBroadcaster broadcasterA;

	@Autowired
	private CacheInvalidator invalidatorA;

	@Autowired
	private CacheManager cacheManagerA;

	@Autowired
	private JdbcTemplate jdbc;

	/**
	 * O caminho de produção inteiro: uma escrita atendida em A limpa o cache de A, grava o sinal, e
	 * o poller de B limpa o cache de B. Nenhum trecho é simulado — a emissão é a que o
	 * {@code CacheInvalidator} usa de verdade.
	 */
	@Test
	void escritaAtendidaEmUmaInstanciaLimpaOCacheDaOutra() {
		String key = uniqueKey();
		Cache cacheA = cacheManagerA.getCache(CacheConfig.LINK_PREVIEWS);
		CacheManager managersB = new ConcurrentMapCacheManager(CacheConfig.LINK_PREVIEWS);
		Cache cacheB = managersB.getCache(CacheConfig.LINK_PREVIEWS);
		assertThat(cacheA).isNotNull();
		assertThat(cacheB).isNotNull();
		cacheA.put(key, "versao-antiga");
		cacheB.put(key, "versao-antiga");

		long before = store.maxSeq();
		InstanceBroadcaster b = instance("pod-b-emissor-" + key);
		InstanceSignalPoller pollerB = poller(b, managersB);

		// Escrita em A: limpa aqui e emite o sinal para as outras.
		invalidatorA.evictAfterCommit(CacheConfig.LINK_PREVIEWS, key);

		assertThat(cacheA.get(key)).as("a instância que atendeu a escrita não espera o próprio sinal")
			.isNull();
		List<String> origins = jdbc.queryForList("""
			select origin_instance from network_instance_signals where signal_type = ? and target = ?
			""", String.class, InstanceSignals.CACHE_INVALIDATED, CacheConfig.LINK_PREVIEWS + "/" + key);
		assertThat(origins).containsExactly(broadcasterA.instanceId());

		pollerB.seek(before);
		assertThat(pollerB.runOnce()).isPositive();
		assertThat(cacheB.get(key)).as("cache invalida em todas as instâncias").isNull();
	}

	/**
	 * A instância que emitiu não se entrega: o poller ignora {@code origin_instance} igual ao
	 * próprio id. Sem isso a escrita local aplicaria o efeito duas vezes — inofensivo para "limpar
	 * cache", mas o contrato é o mesmo para qualquer consumidor futuro.
	 */
	@Test
	void instanciaNaoEntregaOsPropriosSinais() {
		String key = uniqueKey();
		CacheManager managersB = new ConcurrentMapCacheManager(CacheConfig.LINK_PREVIEWS);
		InstanceBroadcaster b = instance("pod-b-proprio-" + key);
		InstanceSignalPoller pollerB = poller(b, managersB);

		long before = store.maxSeq();
		b.publishNow(InstanceSignals.CACHE_INVALIDATED,
			List.of(CacheConfig.LINK_PREVIEWS + "/" + key), "{}");

		pollerB.seek(before);
		assertThat(pollerB.runOnce()).as("sinal da própria instância não é entregue").isZero();
	}

	/** Cada instância anuncia presença com a própria posição de leitura (é o que o painel lê). */
	@Test
	void cadaInstanciaRegistraAPropriaPresenca() {
		String key = uniqueKey();
		CacheManager managersB = new ConcurrentMapCacheManager(CacheConfig.LINK_PREVIEWS);
		InstanceBroadcaster b = instance("pod-b-presenca-" + key);
		InstanceSignalPoller pollerB = poller(b, managersB);

		pollerB.seek(store.maxSeq());
		pollerB.runOnce();

		List<String> readers = jdbc.queryForList(
			"select instance_id from network_instance_readers where instance_id = ?", String.class,
			b.instanceId());
		assertThat(readers).containsExactly(b.instanceId());
	}

	private InstanceBroadcaster instance(String instanceId) {
		return new InstanceBroadcaster(store, Clock.systemUTC(), instanceId, true);
	}

	private InstanceSignalPoller poller(InstanceBroadcaster broadcaster, CacheManager cacheManager) {
		// scheduled=false: o teste decide quando a varredura roda, como no resto da suíte.
		return new InstanceSignalPoller(store, broadcaster,
			List.of(new CacheSignalConsumer(cacheManager)), Clock.systemUTC(), true, false,
			Duration.ofSeconds(300).toSeconds());
	}

	private long signalsFor(String target) {
		Long count = jdbc.queryForObject(
			"select count(*) from network_instance_signals where target = ?", Long.class, target);
		return count == null ? 0 : count;
	}

	/** Alvo único por teste: a base H2 é compartilhada entre as classes da suíte. */
	private static String uniqueKey() {
		return UUID.randomUUID().toString().substring(0, 8);
	}
}