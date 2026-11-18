package com.socialcoisa.rede.paporeto.domain;

/**
 * O estado de um assunto no ranking: o que o tempo diz sobre ele.
 *
 * A classificação é determinística a partir dos números do assunto (potência agora e na
 * janela anterior): EMERGENTE entrou com força agora (a janela anterior não o tinha ou tinha
 * pouco); PERSISTENTE está há tempo no papo (agora e antes, estável); HOT é o meio — esquentou
 * de verdade, veio de um antes que não era nem quase nada nem o tamanho de hoje. O rótulo não é
 * conteúdo: é uma leitura de trajetória, igual para todo mundo que abre a página.
 */
public enum PapoStatus {

	/** Assunto relevante agora que era pouco ou nada na janela anterior: está nascendo. */
	EMERGENTE,

	/** Assunto relevante agora e na janela anterior: a conversa continua. */
	PERSISTENTE,

	/** Assunto que esquentou de verdade agora: nem nasceu do nada nem se manteve estável. */
	HOT;

	/**
	 * O status do assunto, pela potência de agora contra a de uma meia-vida atrás.
	 *
	 * A leitura é de TRAJETÓRIA, e as três faixas são exaustivas: EMERGENTE quando antes havia
	 * no máximo 30% do que há agora (está nascendo); PERSISTENTE quando antes já tinha ao menos
	 * 80% do que tem agora (a conversa continua, no mesmo tamanho); HOT no meio — esquentou de
	 * verdade, veio de um passado que não era nem quase nada nem o tamanho de hoje. A comparação
	 * com o topo da lista não entra no rótulo: a trajetória é do assunto, não da posição dele.
	 */
	public static PapoStatus de(double agora, double antes) {
		if (antes <= agora * FRAÇÃO_QUE_VEM_DE_FORA) {
			return EMERGENTE;
		}
		if (antes >= agora * FRAÇÃO_ESTÁVEL) {
			return PERSISTENTE;
		}
		return HOT;
	}

	/** Emergente: antes tinha no máximo 30% do que tem agora. */
	private static final double FRAÇÃO_QUE_VEM_DE_FORA = 0.30;

	/** Persistente: antes já tinha ao menos 80% do que tem agora — a conversa se sustentou. */
	private static final double FRAÇÃO_ESTÁVEL = 0.80;

}
