package com.socialcoisa.rede.shared.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invalidacao de cache amarrada ao fim da transacao.
 *
 * <p>Escrever e so depois liberar: a escrita pode falhar e voltar atras, e o cache nunca pode
 * passar a servir o resultado de uma transacao que nao commitou. Com transacao aberta a limpeza e
 * registrada para {@code afterCommit}; sem transacao (leitura, script) ela acontece na hora.</p>
 *
 * <p><b>Uma ressalva de arquitetura, de proposito:</b> o cache e por pod
 * ({@link CacheConfig}). Limpar aqui limpa o pod que atendeu a escrita; os outros servem a versao
 * anterior ate o TTL vencer — no pior caso, os 20 segundos de `rede.cache.dynamic-ttl`. Resolver
 * isso exigiria cache compartilhado (Redis), que nao se justifica para dado recalculavel e de
 * frescor curto. O TTL e, por isso, a garantia; a invalidacao e a aceleracao.</p>
 */
@Component
public class CacheInvalidator {

	private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidator.class);

	private final CacheManager cacheManager;

	public CacheInvalidator(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
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