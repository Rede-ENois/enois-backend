package com.socialcoisa.rede.wrapped.application.port.out;

import java.util.List;

import com.socialcoisa.rede.wrapped.domain.WrappedAssunto;

/**
 * O reconhecimento de assuntos: reaproveita o extrator do Papo Reto (normalização, stopwords,
 * unigramas/bigramas, clustering guloso) sobre os textos da própria conta. Determinístico e local,
 * sem serviço externo.
 */
public interface WrappedAssuntos {

	/**
	 * Os assuntos mais frequentes de uma lista de textos, ordenados por ocorrência (desc), com no
	 * máximo {@code limite}. Vazio quando os textos não rendem assunto.
	 */
	List<WrappedAssunto> extrair(List<String> textos, int limite);
}
