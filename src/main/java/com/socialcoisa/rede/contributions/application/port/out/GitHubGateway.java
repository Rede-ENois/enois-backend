package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Porta de leitura da API do GitHub. O adaptador real trata token, limites e cache; os testes usam
 * uma implementação determinística.
 */
public interface GitHubGateway {

	/** PRs fechados/integrados, ordenados por atualização desc, com teto de páginas. */
	List<GitHubPullRequest> listMergedPullRequests(String repository, Instant since, int maxPages);

	/** Issues (sem pull requests) atualizadas desde {@code since}, com teto de páginas. */
	List<GitHubIssue> listIssues(String repository, Instant since, int maxPages);

	/** Completa arquivos e revisões de um PR (chamado só para PR novo ou atualizado). */
	GitHubPullRequest withDetails(String repository, GitHubPullRequest pullRequest);
}
