package com.socialcoisa.rede.network.domain;

/**
 * Modo operacional da rede (docs/rede-distribuida/06-contingencia.md).
 *
 * <p>O modo é dado do Control Plane, não decisão de nó. {@code NORMAL}, {@code HIGH_LOAD} e
 * {@code RECOVERY} são geridos pelo cálculo automático a partir de números medidos;
 * {@code DEGRADED} e {@code EMERGENCY} são decisão humana — dependência crítica instável não é algo
 * que o Control Plane sabe medir, e adivinhar seria pior do que perguntar.</p>
 */
public enum NetworkMode {

	/** Operação padrão. */
	NORMAL,

	/** Fila acima da capacidade disponível: distribuição ampliada. */
	HIGH_LOAD,

	/** Dependência crítica instável: funcionalidades não essenciais desligadas. */
	DEGRADED,

	/** Infra principal indisponível: contingência assume funções preparadas. */
	EMERGENCY,

	/** Voltando ao normal, com drenagem. */
	RECOVERY;

	/**
	 * O modo é gerido pelo cálculo automático? {@code RECOVERY} também é: ele é a saída medida de um
	 * incidente, e voltar a {@code NORMAL} por número é o que fecha o ciclo. {@code DEGRADED} e
	 * {@code EMERGENCY} ficam de fora porque dependência crítica instável não é algo que o Control
	 * Plane mede — quem sabe que o banco está degradado é o humano que olhou.
	 */
	public boolean automatic() {
		return this == NORMAL || this == HIGH_LOAD || this == RECOVERY;
	}

	/** O admin pode acionar este modo à mão? {@code NORMAL}/{@code HIGH_LOAD} são medidos. */
	public boolean manual() {
		return this == DEGRADED || this == EMERGENCY || this == RECOVERY;
	}

	/**
	 * Tarefa nova pode ser designada neste modo?
	 *
	 * <p>{@code DEGRADED} e {@code EMERGENCY} param o despacho: é o que a tabela de contingência
	 * promete ("tarefas assíncronas: adiadas"). O que já está em andamento continua — parar o
	 * despacho não é derrubar trabalho em curso, e o nó ainda entrega o que tem na mão.</p>
	 */
	public boolean dispatchAllowed() {
		return this != DEGRADED && this != EMERGENCY;
	}

	/**
	 * Quantas tarefas designar nesta rodada. {@code RECOVERY} vai pela metade de propósito: retomar
	 * com a fila inteira depois de um incidente é repetir o pico que causou o incidente.
	 */
	public int dispatchLimit(int max) {
		if (!dispatchAllowed()) {
			return 0;
		}
		return this == RECOVERY ? Math.max(1, max / 2) : max;
	}
}
