package com.socialcoisa.rede.figurinhas.domain;

import java.time.Instant;

/** Um passo do histórico da figurinha (cadastro, desativação), com quem fez e quando. */
public record FigurinhaComunidadeEvento(String id, String figurinhaId, String tipo, String atorId, String detalhe,
		Instant criadoEm) {

	public static final String CRIADA = "CRIADA";
	public static final String DESATIVADA = "DESATIVADA";

	public static FigurinhaComunidadeEvento criar(String id, String figurinhaId, String tipo, String atorId,
			String detalhe, Instant quando) {
		return new FigurinhaComunidadeEvento(id, figurinhaId, tipo, atorId, detalhe, quando);
	}
}
