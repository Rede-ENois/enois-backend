package com.socialcoisa.rede.murais.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.murais.domain.Mural;
import com.socialcoisa.rede.murais.domain.MuralContribuicao;

/** Onde os murais e seus elos vivem. Paginação por marca de tempo, como no resto da rede. */
public interface MuraisRepository {

	Mural saveMural(Mural mural);

	Optional<Mural> findMuralById(String id);

	/** Murais ativos criados antes da marca, mais novos primeiro. */
	List<Mural> findActiveMuraisBefore(Instant before, int limit);

	MuralContribuicao saveContribuicao(MuralContribuicao contribuicao);

	Optional<MuralContribuicao> findContribuicao(String muralId, String postId);

	/** Contribuições do mural antes da marca, mais recentes primeiro. */
	List<MuralContribuicao> findContribuicoesByMural(String muralId, Instant before, int limit);

	/** Quantidade de contribuições por mural (só murais com elo contam). */
	Map<String, Long> countContribuicoes(Collection<String> muralIds);

	boolean deleteContribuicao(String id);
}
