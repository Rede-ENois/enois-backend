package com.socialcoisa.rede.support.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportRequest;
import com.socialcoisa.rede.support.domain.SupportStatus;

public interface SupportRequestRepository {

	SupportRequest save(SupportRequest request);

	Optional<SupportRequest> findById(String id);

	Optional<SupportRequest> findByIdForUpdate(String id);

	/** Filtro do painel: categoria e/ou status nulos significam "qualquer". */
	List<SupportRequest> findFiltered(SupportCategory category, SupportStatus status, int limit);

	long countByUserSince(String userId, java.time.Instant since);

	/** Caminho de volta do webhook do GitHub: o evento traz apenas o numero da issue. */
	Optional<SupportRequest> findByGithubIssueNumber(int githubIssueNumber);

	/** Pedidos do proprio solicitante, do mais recente para o mais antigo. */
	List<SupportRequest> findByUserId(String userId, int limit);
}
