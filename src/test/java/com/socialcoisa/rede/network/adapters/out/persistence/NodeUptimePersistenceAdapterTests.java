package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * A regra do contador de disponibilidade (V369), isolada.
 *
 * <p>O que precisa ser decidido uma vez, e sempre igual: <b>este trecho já foi contado?</b> A
 * resposta não pode depender de ordem de chegada, e um trecho atrasado não pode ser recusado — só o
 * reenvio (o mesmo fim do trecho que o dia já registrou) e o que termina no meio de um já contado.
 * </p>
 */
class NodeUptimePersistenceAdapterTests {

	private static final Instant FIM = Instant.parse("2026-11-19T15:03:10Z");

	@Test
	void dia_sem_contato_soma_o_primeiro_trecho() {
		// null e não zero: um dia recém-aberto não tem "último contato" contra o qual comparar.
		assertThat(NodeUptimePersistenceAdapter.deveSomar(null, FIM)).isTrue();
	}

	@Test
	void trecho_posterior_ao_ultimo_contado_soma() {
		assertThat(NodeUptimePersistenceAdapter.deveSomar(FIM.minusSeconds(1), FIM)).isTrue();
	}

	@Test
	void o_reenvio_do_mesmo_contato_nao_soma() {
		// O mesmo heartbeat publicado de novo: o fim é exatamente o último contato já contado.
		assertThat(NodeUptimePersistenceAdapter.deveSomar(FIM, FIM)).isFalse();
	}

	@Test
	void trecho_que_termina_antes_do_ultimo_contado_soma_porque_ainda_nao_estava_contado() {
		// Chegou fora de ordem: é tempo observado que o dia não tinha como contar antes.
		assertThat(NodeUptimePersistenceAdapter.deveSomar(FIM, FIM.minusSeconds(60))).isTrue();
	}

	@Test
	void trecho_que_termina_depois_do_ultimo_contado_soma() {
		assertThat(NodeUptimePersistenceAdapter.deveSomar(FIM, FIM.plusSeconds(60))).isTrue();
	}
}