package com.socialcoisa.rede.shared.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.socialcoisa.rede.shared.broadcast.InstanceBroadcaster;
import com.socialcoisa.rede.shared.broadcast.InstanceSignals;

/**
 * Invalidacao de cache amarrada ao fim da transacao.
 *
 * <p>Escrever e so depois liberar: a escrita pode falhar e voltar atras, e o cache nunca pode
 * passar a servir o resultado de uma transacao que nao commitou. Com transacao aberta a limpeza e
 * registrada para {@code afterCommit}; sem transacao (leitura, script) ela acontece na hora.</p>
 *
 * <p><b>O cache e por pod</b> ({@link CacheConfig}). Limpar aqui limpa o pod que atendeu a escrita;
 * os outros serviam a versao anterior ate o TTL vencer — no pior caso, os 20 segundos de
 * `rede.cache.dynamic-ttl`. Por isso cada limpeza local tambem vira um sinal
 * ({@code CacheInvalidated}) que as outras instancias leem e aplicam no proprio cache: a limpeza
 * chega em ~1 segundo em vez de esperar o TTL. O TTL continua sendo a garantia (sinal perdido nao
 * deixa dado velho para sempre); o sinal e a aceleracao.</p>
 */
@Component
public class CacheInvalidator {

	private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidator.class);

	private final CacheManager cacheManager;
	private final InstanceBroadcaster broadcaster;

	public CacheInvalidator(CacheManager cacheManager, InstanceBroadcaster broadcaster) {
		this.cacheManager = cacheManager;
		this.broadcaster = broadcaster;
	}

	/** Limpa os caches nomeados quando (e se) a transacao atual commitar. */
	public void afterCommit(String... cacheNames) {
		if (cacheNames.length == 0) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					clearNow(cacheNames);
				}
			});
			return;
		}
		clearNow(cacheNames);
	}

	/** Limpeza imediata. Falha de cache nunca derruba a escrita que a pediu. */
	public void clearNow(String... cacheNames) {
		for (String cacheName : cacheNames) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache == null) {
				LOG.warn("cache desconhecido na invalidacao: {}", cacheName);
				continue;
			}
			try {
				cache.clear();
			}
			catch (RuntimeException failure) {
				LOG.warn("falha ao limpar o cache {} ({})", cacheName, failure.getClass().getSimpleName());
			}
			// Avisa as outras instancias depois de limpar aqui: o pod que atendeu a escrita nao
			// espera o proximo ciclo de leitura para ver o proprio efeito.
			broadcaster.publishNow(InstanceSignals.CACHE_INVALIDATED, List.of(cacheName), "{}");
		}
	}

	/** Remove uma entrada especifica (ex.: o perfil publico de quem acabou de se editar). */
	public void evictAfterCommit(String cacheName, Object key) {
		Runnable evict = () -> {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache == null) {
				LOG.warn("cache desconhecido na invalidacao: {}", cacheName);
				return;
			}
			try {
				cache.evict(key);
			}
			catch (RuntimeException failure) {
				LOG.warn("falha ao remover {} de {} ({})", key, cacheName, failure.getClass().getSimpleName());
			}
			broadcaster.publishNow(InstanceSignals.CACHE_INVALIDATED, List.of(cacheName + "/" + key), "{}");
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					evict.run();
				}
			});
			return;
		}
		evict.run();
	}
}