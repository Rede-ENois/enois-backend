package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * As quatro dimensões de saúde do nó (brief §8.8).
 *
 * <p>O brief as exige "obrigatoriamente" e explica por quê: <b>uma máquina pode estar online e a
 * réplica inutilizável</b>. O que estes testes seguram é que cada dimensão responde à sua pergunta,
 * e não à do vizinho — se as quatro colapsassem num estado só, nó sem heartbeat, nó sem versão de
 * runtime, nó sem perfil e nó em quarentena apareceriam todos com o mesmo rótulo, e o operador não
 * saberia onde olhar.</p>
 */
class NodeHealthTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	private static NetworkNode node(NodeStatus status, Set<NodeProfile> profiles, String runtime, Instant lastSeen) {
		return new NetworkNode("nd_h", "no-h", "user-h", status, profiles, "sudeste", "BR", runtime, lastSeen,
			4, 4096, 102400, 100, null, null, null, null, null, 0, null, null);
	}

	private static NetworkNode ativo() {
		return node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_EDGE), "1.0.0", NOW.minusSeconds(10));
	}

	@Test
	void no_saudavel_e_OK_nas_quatro_dimensoes() {
		NodeHealth h = NodeHealth.of(ativo(), 0, NOW);

		assertThat(h.host()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.runtime()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.application()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.network()).isEqualTo(NodeHealth.State.OK);
	}

	@Test
	void heartbeat_velho_derruba_host_e_participacao_mas_nao_faz_o_runtime_sumir() {
		// Máquina que parou de falar: o host não responde e por isso não participa. Mas o runtime
		// que ela declarou continua sendo o que ela declarou — "não respondeu" não desmente a versão.
		NetworkNode calado = node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_EDGE), "1.0.0",
			NOW.minus(NodeHealth.ALIVE_WINDOW).minusSeconds(1));

		NodeHealth h = NodeHealth.of(calado, 0, NOW);

		assertThat(h.host()).isEqualTo(NodeHealth.State.FAILING);
		assertThat(h.network()).isEqualTo(NodeHealth.State.FAILING);
		assertThat(h.runtime()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.application()).isEqualTo(NodeHealth.State.OK);
	}

	@Test
	void a_janela_de_vivo_e_inclusiva_na_fronteira() {
		// Exatamente no limite ainda está vivo (lastSeenAt.isAfter agora - janela); é a mesma janela
		// do scheduler e do painel, e uma fronteira diferente faria o nó estar vivo num quadro e
		// morto no outro.
		NetworkNode naFronteira = node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_EDGE), "1.0.0",
			NOW.minus(NodeHealth.ALIVE_WINDOW).plusSeconds(1));
		NetworkNode foraDaJanela = node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_EDGE), "1.0.0",
			NOW.minus(NodeHealth.ALIVE_WINDOW));

		assertThat(NodeHealth.of(naFronteira, 0, NOW).host()).isEqualTo(NodeHealth.State.OK);
		assertThat(NodeHealth.of(foraDaJanela, 0, NOW).host()).isEqualTo(NodeHealth.State.FAILING);
	}

	@Test
	void runtime_sem_versao_e_DEGRADED_e_nao_falha() {
		// Runtime anterior ao campo: o agente está vivo e atende, só não se identifica. Chamar isso
		// de falha tiraria do balanceamento máquinas que trabalham.
		NodeHealth h = NodeHealth.of(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_EDGE), " ", NOW.minusSeconds(5)),
			0, NOW);

		assertThat(h.runtime()).isEqualTo(NodeHealth.State.DEGRADED);
		assertThat(h.host()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.network()).isEqualTo(NodeHealth.State.OK);
	}

	@Test
	void no_sem_perfil_esta_de_pe_e_nao_serve_ninguem() {
		// A distinção que o brief pede: online não é o mesmo que útil. Host OK, aplicação FAILING.
		NodeHealth h = NodeHealth.of(node(NodeStatus.ACTIVE, Set.of(), "1.0.0", NOW.minusSeconds(5)), 0, NOW);

		assertThat(h.host()).isEqualTo(NodeHealth.State.OK);
		assertThat(h.application()).isEqualTo(NodeHealth.State.FAILING);
	}

	@Test
	void atraso_e_falhas_seguidas_sao_DEGRADED_da_aplicacao() {
		NetworkNode falhando = new NetworkNode("nd_h", "no-h", "user-h", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_EDGE), "sudeste", "BR", "1.0.0", NOW.minusSeconds(5), 4, 4096, 102400, 100,
			null, null, null, null, null, NetworkNode.FAILURE_STREAK_LIMIT, null, null);

		assertThat(NodeHealth.of(falhando, 0, NOW).application()).isEqualTo(NodeHealth.State.DEGRADED);
		assertThat(NodeHealth.of(ativo(), 7, NOW).application()).isEqualTo(NodeHealth.State.DEGRADED);
		// Atraso não tira o nó da rede: ele continua recebendo trabalho enquanto se atualiza.
		assertThat(NodeHealth.of(ativo(), 7, NOW).network()).isEqualTo(NodeHealth.State.OK);
	}

	@Test
	void quarentena_e_status_que_nao_opera_derrubam_so_a_participacao() {
		// Decisão administrativa: a máquina está saudável e fora do balanceamento. É o caso que o
		// operador precisa ver sem abrir o histórico — e que sumiria num estado único.
		NetworkNode suspenso = node(NodeStatus.SUSPENDED, Set.of(NodeProfile.NODE_EDGE), "1.0.0",
			NOW.minusSeconds(5));
		NetworkNode emQuarentena = new NetworkNode("nd_h", "no-h", "user-h", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_EDGE), "sudeste", "BR", "1.0.0", NOW.minusSeconds(5), 4, 4096, 102400, 100,
			null, null, null, null, null, 0, NOW.plusSeconds(600), "resultado divergente");

		for (NetworkNode n : new NetworkNode[] { suspenso, emQuarentena }) {
			NodeHealth h = NodeHealth.of(n, 0, NOW);
			assertThat(h.host()).isEqualTo(NodeHealth.State.OK);
			assertThat(h.network()).isEqualTo(NodeHealth.State.FAILING);
		}
	}

	@Test
	void saturado_continua_na_rede_mas_com_ressalva() {
		// Trabalhando no limite: está na rede (não é FAILING), só não é "tudo bem".
		NetworkNode saturado = new NetworkNode("nd_h", "no-h", "user-h", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_EDGE), "sudeste", "BR", "1.0.0", NOW.minusSeconds(5), 4, 4096, 102400, 100,
			NetworkNode.SATURATED_CPU_PERCENT, null, null, null, null, 0, null, null);

		assertThat(NodeHealth.of(saturado, 0, NOW).network()).isEqualTo(NodeHealth.State.DEGRADED);
	}
}