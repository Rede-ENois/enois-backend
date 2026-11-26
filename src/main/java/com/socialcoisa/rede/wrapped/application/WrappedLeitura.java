package com.socialcoisa.rede.wrapped.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento;

/**
 * A leitura paginada do histórico, injetada como função para o agregador não conhecer Spring, banco
 * nem transação — e para o teste poder simular falha/retomada sem infraestrutura.
 */
@FunctionalInterface
public interface WrappedLeitura {

	/**
	 * A página de eventos depois do cursor {@code depoisDeCursor} (null = do começo), até o limite.
	 * A paginação é por chave: o cursor é a chave da última linha entregue, e a próxima consulta
	 * pede as maiores chaves depois dele — estável mesmo com fatos de mesma data.
	 */
	List<WrappedEvento> pagina(String userId, Instant de, Instant ate, String depoisDeCursor, int limite);
}
