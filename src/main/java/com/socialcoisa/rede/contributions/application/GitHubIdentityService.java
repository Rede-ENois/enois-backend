package com.socialcoisa.rede.contributions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.port.out.GitHubIdentityRepository;
import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.contributions.domain.GitHubSyncState;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Vínculo GitHub ↔ Énóis. O ID numérico do GitHub é a chave; o login pode mudar e é atualizado.
 * Uma conta GitHub só pode estar ligada a uma conta Énóis, e vice-versa.
 */
@Service
public class GitHubIdentityService {

	private final GitHubIdentityRepository repository;
	private final Clock clock;

	public GitHubIdentityService(GitHubIdentityRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public GitHubIdentity remember(String userId, String githubUserId, String githubLogin) {
		if (!StringUtils.hasText(userId) || !StringUtils.hasText(githubUserId)) {
			throw new ApiException(400, "INVALID_GITHUB_IDENTITY", "Identidade GitHub inválida.");
		}
		String login = cleanLogin(githubLogin);
		var byExternal = repository.findByGithubUserId(githubUserId);
		if (byExternal.isPresent() && !byExternal.get().userId().equals(userId)) {
			throw new ApiException(409, "GITHUB_ACCOUNT_IN_USE",
				"Esta conta GitHub já está vinculada a outro perfil do Énóis.");
		}
		var current = repository.findByUserId(userId);
		if (current.isPresent() && !current.get().githubUserId().equals(githubUserId)) {
			throw new ApiException(409, "GITHUB_ALREADY_LINKED",
				"Sua conta já tem outro GitHub vinculado. Desvincule antes de trocar.");
		}
		GitHubIdentity identity = new GitHubIdentity(
			current.map(GitHubIdentity::id).orElse("ghid_" + UUID.randomUUID().toString().replace("-", "")),
			userId, githubUserId, login, current.map(GitHubIdentity::linkedAt).orElse(clock.instant()),
			current.map(GitHubIdentity::lastSyncedAt).orElse(null),
			current.map(GitHubIdentity::syncState).orElse(GitHubSyncState.STALE),
			current.map(GitHubIdentity::lastSyncError).orElse(null));
		return repository.save(identity);
	}

	@Transactional
	public void unlink(String userId) {
		repository.deleteByUserId(userId);
	}

	public GitHubIdentityView status(String userId) {
		return repository.findByUserId(userId)
			.map(identity -> new GitHubIdentityView(true, identity.githubLogin(), identity.linkedAt(),
				identity.lastSyncedAt(), identity.syncState(), identity.lastSyncError()))
			.orElse(new GitHubIdentityView(false, null, null, null, null, null));
	}

	private static String cleanLogin(String login) {
		String clean = login == null ? "" : login.replaceAll("[^A-Za-z0-9-]", "").trim();
		if (clean.isBlank() || clean.length() > 100) {
			throw new ApiException(400, "INVALID_GITHUB_LOGIN", "Login do GitHub inválido.");
		}
		return clean;
	}

	public record GitHubIdentityView(boolean linked, String login, Instant linkedAt, Instant lastSyncedAt,
			GitHubSyncState syncState, String lastSyncError) {
	}
}
