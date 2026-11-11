package com.socialcoisa.rede.network.domain;

/**
 * Modo operacional da rede (docs/rede-distribuida/06-contingencia.md).
 *
 * <p>O modo é dado do Control Plane, não decisão de nó. Na fase 2 só {@code NORMAL} e
 * {@code HIGH_LOAD} são calculados automaticamente, a partir de números medidos; os demais são
 * decisão humana da fase 4 e o cálculo automático não os sobrescreve.</p>
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

	/** Só estes dois são geridos pelo cálculo automático da fase 2. */
	public boolean automatic() {
		return this == NORMAL || this == HIGH_LOAD;
	}
}
