package com.socialcoisa.rede.network.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * As quatro dimensões de saúde de um nó (brief §8.8): host, runtime, aplicação e participação na
 * rede.
 *
 * <p>O brief as exige "obrigatoriamente" e explica o motivo: <b>uma máquina pode estar online e a
 * réplica estar inutilizável</b>. Um único "verde/vermelho" afundava os quatro casos num só — o nó
 * que perdeu o heartbeat (rede), o que respondeu sem CPU/memória (agent), o que não manda versão de
 * runtime (instalação antiga) e o que está suspenso por decisão administrativa apareciam todos como
 * "offline", e o operador não sabia onde olhar.</p>
 *
 * <p>O estado de cada dimensão é derivado <b>só do que o Control Plane mediu</b> — nunca de uma
 * sonda ao vivo: o nó não abre porta (o modelo é pull), então "está vivo" é o heartbeat dentro da
 * janela, a mesma do scheduler, do painel e da série.</p>
 *
 * <p>Cada dimensão é sempre um dos três estados: o Control Plane conhece o nó (veio do banco), então
 * não há "não medido" no nível da dimensão — o que é desconhecido tem estado próprio, e é por isso
 * que "não reportou versão" é {@code DEGRADED}, não uma ausência silenciosa.</p>
 *
 * @param host        o processo do nó responde (heartbeat recente)
 * @param runtime     a instalação é de uma versão que se identifica
 * @param application o nó executa o que lhe foi autorizado, e o está executando com sucesso
 * @param network     o nó participa do balanceamento da rede (vivo, autorizado, aceitando tarefa)
 */
public record NodeHealth(NodeHealth.State host, NodeHealth.State runtime, NodeHealth.State application,
		NodeHealth.State network) {

	/** {@code OK} = bem; {@code DEGRADED} = atende, mas não plenamente; {@code FAILING} = inutilizável. */
	public enum State {
		OK, DEGRADED, FAILING
	}

	/**
	 * Janela de "vivo" — 180 s, a mesma do scheduler, do painel e da série. Um segundo corte faria o
	 * nó estar vivo numa dimensão e morto na outra no mesmo quadro.
	 */
	public static final Duration ALIVE_WINDOW = Duration.ofSeconds(180);

	/**
	 * @param behindAggregates quantos agregados deste nó estão em versão anterior à da origem
	 */
	public static NodeHealth of(NetworkNode node, long behindAggregates, Instant now) {
		return new NodeHealth(host(node, now), runtime(node), application(node, behindAggregates), network(node, now));
	}

	/**
	 * Host: o processo está mandando heartbeat.
	 *
	 * <p>Um heartbeat velho não diz que a máquina caiu — diz que <b>não se sabe</b> se está de pé. Por
	 * isso é {@code FAILING} e não "offline": a dimensão mede o que respondeu, e o que não respondeu
	 * não pode ser afirmado saudável.</p>
	 */
	static State host(NetworkNode node, Instant now) {
		return node.alive(now, ALIVE_WINDOW) ? State.OK : State.FAILING;
	}

	/**
	 * Runtime: a instalação se identifica.
	 *
	 * <p>Sem versão reportada, o nó roda um runtime anterior ao campo, e não há como saber o que ele
	 * suporta. Ele continua atendendo ({@code DEGRADED}, não {@code FAILING}): o agente está vivo, só
	 * não se dá a conhecer. Chamar isso de falha tiraria do balanceamento máquinas que trabalham.</p>
	 */
	static State runtime(NetworkNode node) {
		return node.runtimeVersion() == null || node.runtimeVersion().isBlank() ? State.DEGRADED : State.OK;
	}

	/**
	 * Aplicação: o nó executa o que lhe foi autorizado, e executa bem.
	 *
	 * <p>Sem perfil, o nó está vivo mas não faz nada pela rede — é {@code FAILING} <b>da aplicação</b>
	 * (e da participação): a máquina está de pé e não serve a ninguém. Sequência de falhas é
	 * {@code DEGRADED}: o nó atende, com defeito. Atraso de sincronização também: quantos agregados
	 * dependem de quantos existem na origem, e essa proporção não está aqui.</p>
	 *
	 * @param behindAggregates quantos agregados deste nó estão em versão anterior à da origem
	 */
	static State application(NetworkNode node, long behindAggregates) {
		if (node.profiles().isEmpty()) {
			return State.FAILING;
		}
		return node.failing() || behindAggregates > 0 ? State.DEGRADED : State.OK;
	}

	/**
	 * Participação na rede: o nó pode e quer receber trabalho.
	 *
	 * <p>Separada da aplicação de propósito — a máquina pode estar saudável e fora do balanceamento
	 * por decisão administrativa, e é isso que o operador precisa ver sem ir ao histórico. Saturado
	 * ou sem vaga é {@code DEGRADED} (está trabalhando, no limite); quarentena, status que não opera,
	 * sem perfil ou sem heartbeat são {@code FAILING}.</p>
	 */
	static State network(NetworkNode node, Instant now) {
		if (!node.alive(now, ALIVE_WINDOW) || !node.canOperate() || node.quarantined(now)) {
			return State.FAILING;
		}
		return node.saturated() || !node.hasCapacity() ? State.DEGRADED : State.OK;
	}
}