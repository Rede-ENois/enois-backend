package com.socialcoisa.rede.murais.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.murais.domain.Mural;
import com.socialcoisa.rede.murais.domain.MuralContribuicao;

/** Onde os murais e seus elos vivem. Paginação por marca de tempo, como no resto da rede. */
public interface MuraisRepository {

	Mural saveMural(Mural mural);

	Optional<Mural> findMuralById(String id);

	/** Murais ativos criados antes da marca, mais novos primeiro. */
	List<Mural> findActiveMuraisBefore(Instant before, int limit);

	/**
	 * A mesma lista, filtrada por `search` no título ou na descrição (sem diferenciar maiúsculas).
	 * `search` vazio é a lista inteira; o termo já vem aparado pelo serviço.
	 */
	List<Mural> findActiveMuraisBefore(Instant before, String search, int limit);

	/** Todos os murais ativos de um dono, mais novos primeiro — o seletor do post lista tudo. */
	List<Mural> findActiveMuraisByOwner(UserId ownerId);

	MuralContribuicao saveContribuicao(MuralContribuicao contribuicao);

	Optional<MuralContribuicao> findContribuicao(String muralId, String postId);

	/** Murais (do conjunto) em que o post já está colado — a flag "tem" do seletor. */
	Collection<String> findMuralIdsComPost(String postId, Collection<String> muralIds);

	/** Contribuições do mural antes da marca, mais recentes primeiro. */
	List<MuralContribuicao> findContribuicoesByMural(String muralId, Instant before, int limit);

	/** Quantidade de contribuições por mural (só murais com elo contam). */
	Map<String, Long> countContribuicoes(Collection<String> muralIds);

	boolean deleteContribuicao(String id);
}
