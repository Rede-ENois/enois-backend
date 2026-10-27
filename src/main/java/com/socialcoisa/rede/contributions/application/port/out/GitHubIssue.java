package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Issue do GitHub como veio da API. Pull requests são descartados na leitura: eles têm endpoint e
 * critério próprios e não podem ser contados duas vezes.
 */
public record GitHubIssue(long number, String nodeId, String title, String url, String authorId,
		String authorLogin, String state, Instant openedAt, Instant closedAt, Instant updatedAt,
		List<String> labels) {
}
