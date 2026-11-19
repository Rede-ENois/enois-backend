package com.socialcoisa.rede.desafios.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.desafios.domain.Desafio;
import com.socialcoisa.rede.desafios.domain.Participacao;

public interface DesafiosRepository {

	Desafio saveDesafio(Desafio desafio);

	Optional<Desafio> findDesafioById(String desafioId);

	/** A lista do espaço: no ar primeiro (os que encerram logo, antes), depois as histórias. */
	List<Desafio> findFeedBefore(Instant before, int limit);

	/** Os desafios do próprio autor (para "convoco outro?" e o acompanhamento). */
	List<Desafio> findActiveByAuthor(String authorId);

	Participacao saveParticipacao(Participacao participacao);

	Optional<Participacao> findParticipacaoById(String participacaoId);

	/** A participação da pessoa no desafio (uma por pessoa; republicar substitui). */
	Optional<Participacao> findParticipacaoDoAutor(String desafioId, String authorId);

	/** As participações no ar do desafio, da mais antiga à mais nova (o mural do desafio). */
	List<Participacao> findActiveParticipacoesByDesafio(String desafioId);

	/** Quantas participações no ar o desafio tem — o contador do card. */
	long countActiveParticipacoes(String desafioId);

	/** Torcida: uma por pessoa por participação; a chave é o próprio id da linha. */
	void cheer(String participacaoId, String userId);

	void uncheer(String participacaoId, String userId);

	/** Totais de torcida por participação — contagem, nunca a lista de quem torceu. */
	Map<String, Long> countCheersByParticipacaoIds(List<String> participacaoIds);

	/** Quem lê torceu? (participação → true) — acende a torcida própria. */
	Set<String> findUserCheers(String userId, Set<String> participacaoIds);
}
