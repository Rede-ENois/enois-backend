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
	 * Evidência medida de que a <b>rede</b> não está inteira — o que a automação pode afirmar sem
	 * adivinhar o que o humano sabe (brief §12 e §4).
	 *
	 * <p>Estes dois sinais antes só existiam no documento: o banco que não serve para captura e a
	 * divergência de dado ficavam visíveis no endpoint, mas o modo continuava {@code NORMAL} — um
	 * painel dizendo "captura parada" ao lado de um modo dizendo "operações normais".</p>
	 */
	public enum HealthSignal {

		/** Nada medido: as duas verificações não apontaram problema (ou não foram feitas). */
		NONE,

		/** O banco principal não serve para captura de alterações (a instalação declarou exigir). */
		CAPTURE_NOT_READY,

		/** Há evidência de divergência detectada — o caso mais grave dos dois. */
		DATA_DIVERGENCE;

		/**
		 * O sinal a partir do que foi medido.
		 *
		 * <p><b>"Não sei" não é problema.</b> {@code captureReady} nulo é "não foi possível
		 * perguntar ao banco" — H2 da suíte, usuário sem permissão —, e tratar isso como não-pronto
		 * marcaria todo ambiente de teste como degradado e esconderia a diferença que o operador
		 * precisa ver. {@code captureRequired} falso é "a instalação não decidiu usar captura": a
		 * ausência dela é o estado atual, não um defeito.</p>
		 */
		public static HealthSignal of(Boolean captureReady, boolean captureRequired) {
			return of(captureReady, captureRequired, false);
		}

		/** Com divergência medida, o motivo é o divergir — é o pior dos dois. */
		public static HealthSignal of(Boolean captureReady, boolean captureRequired, boolean diverged) {
			if (diverged) {
				return DATA_DIVERGENCE;
			}
			if (captureRequired && Boolean.FALSE.equals(captureReady)) {
				return CAPTURE_NOT_READY;
			}
			return NONE;
		}
	}

	/**
	 * O modo que a evidência exige. {@code DEGRADED} continua sendo de decisão humana para o que o
	 * Control Plane <b>não</b> mede; esta é a transição para o que ele mede.
	 *
	 * <p>{@code EMERGENCY} não é rebaixado: infra principal indisponível já é o pior estado e a
	 * degradação não diz nada além dele. {@code RECOVERY} também não: retomar com a captura parada é
	 * repetir o incidente, e o despacho pela metade protege mais do que o despacho normal.</p>
	 */
	public NetworkMode degradedBy(HealthSignal signal) {
		if (signal == HealthSignal.NONE || this == EMERGENCY || this == RECOVERY) {
			return this;
		}
		return DEGRADED;
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
