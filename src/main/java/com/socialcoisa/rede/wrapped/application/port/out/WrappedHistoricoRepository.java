package com.socialcoisa.rede.wrapped.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * O histórico de UMA conta no intervalo do ano — a matéria-prima da retrospectiva.
 *
 * <p>Este é o único ponto que toca o feed: devolve eventos recortados (autor, data, publicação,
 * texto) e nada mais. A leitura deve ser paginada no banco (a conta pode ter milhares de linhas):
 * o agregador chama página a página, nunca carrega o ano inteiro de uma vez.</p>
 *
 * <p>Conteúdo removido, quarentenado ou de conta removida não sai daqui: a consulta filtra
 * {@code status = ACTIVE} e só devolve linhas do histórico válido.</p>
 */
public interface WrappedHistoricoRepository {

	/** Uma página de eventos da conta no intervalo, depois de {@code depoisDeCursor}. */
	List<WrappedEvento> pagina(String userId, Instant de, Instant ate, String depoisDeCursor, int limite);
}
