package com.socialcoisa.rede.wrapped.application.port.out;

import java.util.List;

/** A leitura das contas elegíveis e da credibilidade, para construir a fila e priorizar. */
public interface WrappedContasRepository {

	/** Uma página de contas elegíveis (ordem estável por id), depois de {@code depoisDeUserId}. */
	List<WrappedConta> pagina(String depoisDeUserId, int limite);

	/** A credibilidade atual de uma conta (0 a 1000), ou null quando não existe. */
	Integer credibilidade(String userId);
}
