package com.socialcoisa.rede.moderation.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;

/** Livro-caixa da checagem de conteúdo automatizado: uma linha por post, sem repetir trabalho. */
public interface ContentAutomationCheckRepository {

	Optional<ContentAutomationCheck> findByPost(String postId);

	ContentAutomationCheck save(ContentAutomationCheck check);

	/** Muda o estado da revisão (liberado/removido) mantendo o veredito original. */
	void updateStatus(String postId, ReviewStatus status);

	/** Fila de revisão: posts em quarentena aguardando uma pessoa, do mais recente para o mais antigo. */
	List<ContentAutomationCheck> findPending(int limit);

	/**
	 * Ids de posts ativos ainda sem checagem, dos mais recentes para os mais antigos. É o que a
	 * varredura usa — o que já foi avaliado não volta para a fila.
	 */
	List<String> findUncheckedPostIdsSince(Instant since, int limit);
}