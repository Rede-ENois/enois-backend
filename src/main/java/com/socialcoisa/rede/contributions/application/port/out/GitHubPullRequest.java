package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Dados de um PR já integrado, como vieram da API do GitHub. O adaptador real preenche os detalhes
 * (arquivos/revisões) sob demanda; o serviço de sincronização só persiste o que é evidência.
 */
public record GitHubPullRequest(long number, String nodeId, String title, String url, String body,
		String authorId, String authorLogin, String state, Instant openedAt, Instant mergedAt,
		Instant updatedAt, int additions, int deletions, int changedFiles, List<String> files,
		int reviewsCount, int approvalsCount, int changesRequestedCount) {
}
