package com.socialcoisa.rede.contributions.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Porta de leitura da API do GitHub. O adaptador real trata token, limites e cache; os testes usam
 * uma implementação determinística.
 */
public interface GitHubGateway {

	/**
	 * Nomes completos ({@code org/repo}) dos repositórios públicos da organização, sem forks nem
	 * arquivados. Repositório privado nunca entra: o título de PR/issue vira evidência pública.
	 */
	default List<String> listOrganizationRepositories(String organization, int maxPages) {
		return List.of();
	}

	/** PRs fechados/integrados, ordenados por atualização desc, com teto de páginas. */
	List<GitHubPullRequest> listMergedPullRequests(String repository, Instant since, int maxPages);

	/** Issues (sem pull requests) atualizadas desde {@code since}, com teto de páginas. */
	List<GitHubIssue> listIssues(String repository, Instant since, int maxPages);

	/** Completa arquivos e revisões de um PR (chamado só para PR novo ou atualizado). */
	GitHubPullRequest withDetails(String repository, GitHubPullRequest pullRequest);

	/** PRs abertos do repositório, para a fila de revisão do painel. */
	List<GitHubPullRequest> listOpenPullRequests(String repository, int maxPages);

	/**
	 * Envia uma revisão (APPROVE / REQUEST_CHANGES / COMMENT). {@code tokenOverride} revisa em nome
	 * de quem vinculou o GitHub com escrita; nulo usa o token de serviço.
	 */
	boolean submitReview(String repository, long number, String decision, String body, String tokenOverride);
}
