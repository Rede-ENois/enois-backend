package com.socialcoisa.rede.network.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aquisição periódica do cache de borda (fase 5).
 *
 * <p>O executor {@code EDGE_CACHE} só roda quando recebe tarefa. Sem esta varredura, o cache de
 * borda seria aquecido apenas pela rota admin — e um nó que ficou frio por {@code reset} ou que
 * entrou agora serviria conteúdo velho até alguém abrir o painel.</p>
 *
 * <p>A varredura é deliberadamente barata: a cada ciclo ela só olha nós de borda vivos e checa se
 * cada um tem pedido em aberto. Nó com pedido em aberto é pulado — o trabalho dele ainda está na
 * fila ou em execução, e enfileirar de novo encheria a fila do nó de tarefas que não avançam o
 * cursor.</p>
 *
 * <p>Desligável por propriedade para a suíte chamar {@link #runOnce()} na mão, como os demais
 * workers do módulo.</p>
 */
@Component
class EdgeCacheWorker {

	private final EdgeCacheService edge;
	private final boolean enabled;

	EdgeCacheWorker(EdgeCacheService edge,
			@Value("${rede.network.edge.cache.enabled:true}") boolean enabled) {
		this.edge = edge;
		this.enabled = enabled;
	}

	@Scheduled(initialDelayString = "${rede.network.edge.cache.initial-delay-ms:90000}",
			fixedDelayString = "${rede.network.edge.cache.delay-ms:120000}")
	void sweep() {
		if (!enabled) {
			return;
		}
		runOnce();
	}

	int runOnce() {
		return edge.sweep();
	}
}