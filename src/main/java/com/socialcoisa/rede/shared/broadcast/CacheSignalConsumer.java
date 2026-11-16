package com.socialcoisa.rede.shared.broadcast;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * "O cache X mudou": limpa a entrada neste pod.
 *
 * <p>É a metade que faltava do aviso que o {@code CacheInvalidator} já documenta — invalidar no pod
 * que atendeu a escrita não invalidava os outros, e o outro pod servia a versão anterior até o TTL
 * vencer. Com o sinal, a limpeza chega em todas as instâncias em ~1 segundo em vez de até 20.</p>
 *
 * <p>O sinal nomeia o cache e, opcionalmente, a chave ({@code "publicProfiles/i:u_123"}). Sem
 * chave, limpa o cache inteiro; com chave, remove só aquela entrada — o mesmo par que o
 * {@code CacheInvalidator} expõe. Nunca carrega o conteúdo: quem limpa não precisa saber o que
 * estava lá, e o valor é recalculado pela próxima leitura.</p>
 */
@Component
class CacheSignalConsumer implements LocalSignalConsumer {

	private final CacheManager cacheManager;

	CacheSignalConsumer(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	@Override
	public String signalType() {
		return InstanceSignals.CACHE_INVALIDATED;
	}

	@Override
	public void deliver(InstanceSignal signal) {
		String entry = signal.target();
		int slash = entry.indexOf('/');
		String cacheName = slash < 0 ? entry : entry.substring(0, slash);
		String key = slash < 0 ? null : entry.substring(slash + 1);
		Cache cache = cacheManager.getCache(cacheName);
		if (cache == null) {
			return;
		}
		if (key == null || key.isBlank()) {
			cache.clear();
			return;
		}
		cache.evict(key);
	}
}