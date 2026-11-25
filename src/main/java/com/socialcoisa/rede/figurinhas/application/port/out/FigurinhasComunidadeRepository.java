package com.socialcoisa.rede.figurinhas.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidadeEvento;

/** Persistência das figurinhas da comunidade e do histórico delas. */
public interface FigurinhasComunidadeRepository {

	FigurinhaComunidade save(FigurinhaComunidade figurinha);

	Optional<FigurinhaComunidade> findById(String id);

	/** A ATIVA com esse código (desativada nunca conta: o código dela está livre). */
	Optional<FigurinhaComunidade> findAtivaByCodigo(String codigo);

	List<String> ativosEntre(Collection<String> ids);

	/** Página do catálogo (só ativas), mais novas primeiro; termo vazio = tudo. */
	List<FigurinhaComunidade> page(String query, int offset, int limit);

	long count(String query);

	long countAtivasDoAutor(String autorId);

	List<FigurinhaComunidadeEvento> eventos(String figurinhaId);

	void saveEvento(FigurinhaComunidadeEvento evento);
}
