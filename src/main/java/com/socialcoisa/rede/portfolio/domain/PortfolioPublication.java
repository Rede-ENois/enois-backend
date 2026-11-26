package com.socialcoisa.rede.portfolio.domain;

import java.time.Instant;
import java.util.List;

/**
 * Uma publicação do portfólio: o retrato exato do que foi para o ar naquela versão.
 *
 * <p>Guardar o retrato (em vez de só apontar para o rascunho) é o que permite ler um portfólio
 * público sem reconstruí-lo e o que deixa a fase de "desfazer" ler uma versão antiga sem depender
 * de nenhuma peça que já foi editada ou apagada.</p>
 */
public record PortfolioPublication(String id, String userId, int version, String html,
		List<PortfolioBlock> blocks, Instant publishedAt) {
}
