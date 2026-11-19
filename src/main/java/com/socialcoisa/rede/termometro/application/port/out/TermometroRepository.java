package com.socialcoisa.rede.termometro.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * Onde as fatias e os cortes do Termômetro dormem.
 *
 * A fatia é a CONTABILIDADE do corte incremental: cada manifestação classificada vira uma linha
 * com (bucket da hora de publicação, fonte, emoção, autor, momento da leitura). O `lidaEm` é a
 * fronteira real de leitura — o próximo corte lê as fontes a partir dele, e é o que impede que
 * uma releitura da mesma janela duplique fatia. Os cortes agregados são REESCRITOS a cada corte
 * (uma linha por janela), sempre a partir das fatias — nunca relendo as fontes.
 */
public interface TermometroRepository {

	/** Grava as manifestações classificadas de um corte (uma linha por manifestação). */
	void salvarFatias(List<Fatia> fatias);

	/** As fatias cujo bucket de publicação cai em [de, ate) — a matéria-prima do corte. */
	List<Fatia> fatiasEntre(Instant de, Instant ate);

	/** A última fronteira de leitura gravada (o maior `lidaEm`); vazio na primeira vez. */
	Optional<Instant> ultimaFronteira();

	/** Sobrescreve o corte agregado da janela. */
	void salvarCorte(CorteTermico corte);

	/** O corte agregado atual da janela; vazio quando o worker ainda não passou por ela. */
	Optional<CorteTermico> corteDe(TermometroPeriodo periodo);

	/** A fatia crua do Termômetro: emoção por bucket, com o autor só para a contagem distinta. */
	record Fatia(Instant hora, String fonte, String emocao, String autorId, Instant lidaEm) {
	}

}
