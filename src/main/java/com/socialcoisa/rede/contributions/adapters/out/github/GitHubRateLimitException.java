package com.socialcoisa.rede.contributions.adapters.out.github;

/** Limite da API do GitHub atingido: a sincronização para e tenta de novo depois. */
public class GitHubRateLimitException extends RuntimeException {

	public GitHubRateLimitException(String message) {
		super(message);
	}
}
