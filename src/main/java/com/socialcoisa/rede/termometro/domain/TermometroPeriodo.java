package com.socialcoisa.rede.termometro.domain;

/**
 * As janelas de tempo do Termômetro: o horizonte que o painel mostra de cada vez.
 *
 * A duração vive no domínio (e não no banco) porque a janela é semântica: "hoje" é o dia de quem
 * vê, no fuso da plataforma, e muda de comprimento conforme a hora. O worker reescreve a linha
 * do corte a cada passagem; a leitura nunca soma janelas.
 */
public enum TermometroPeriodo {

	/** Do começo do dia corrente até agora. */
	HOJE,

	/** Últimas 24 horas, deslizantes. */
	ULTIMAS_24H,

	/** Últimos 7 dias, deslizantes. */
	ULTIMOS_7D,

	/** Últimos 30 dias, deslizantes. */
	ULTIMOS_30D;

	/** As janelas em ordem de exibição no painel: do mais imediato ao mais longo. */
	public static TermometroPeriodo[] emOrdemDePainel() {
		return new TermometroPeriodo[] { HOJE, ULTIMAS_24H, ULTIMOS_7D, ULTIMOS_30D };
	}
}
