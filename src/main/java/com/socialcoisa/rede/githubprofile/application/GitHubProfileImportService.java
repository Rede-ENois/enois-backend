package com.socialcoisa.rede.githubprofile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.port.out.GitHubIdentityRepository;
import com.socialcoisa.rede.contributions.domain.GitHubIdentity;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubProfileReadme;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubUserRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.projects.application.ProjectsService;
import com.socialcoisa.rede.projects.domain.UserProject;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Importa o que já é público no GitHub para o perfil: repositórios viram cards de projeto (com o
 * link apontando para o repositório) e o README do perfil vira a bio em HTML.
 *
 * <p>O vínculo GitHub é obrigatório e o handle vem dele, nunca do corpo. Só entram repositórios
 * que a API devolveu para aquele login; o projeto passa pelas mesmas regras/limites do cadastro
 * manual. Nada guarda token do usuário: a leitura usa o token fine-grained de leitura do serviço.</p>
 */
@Service
public class GitHubProfileImportService {

	private static final int MAX_IMPORT = 20;
	private static final int MAX_STACK = 10;
	private static final int MAX_STACK_ITEM = 40;

	private final CachedGitHubProfileReader github;
	private final GitHubIdentityRepository identities;
	private final UserProfileRepository profiles;
	private final ProjectsService projects;
	private final MarkdownBioRenderer markdown;
	private final Clock clock;

	public GitHubProfileImportService(CachedGitHubProfileReader github, GitHubIdentityRepository identities,
			UserProfileRepository profiles, ProjectsService projects, MarkdownBioRenderer markdown, Clock clock) {
		this.github = github;
		this.identities = identities;
		this.profiles = profiles;
		this.projects = projects;
		this.markdown = markdown;
		this.clock = clock;
	}

	public List<GitHubUserRepository> repositories(String userId) {
		return github.repositories(login(userId));
	}

	public ReadmePreview readmePreview(String userId) {
		String login = login(userId);
		GitHubProfileReadme readme = github.profileReadme(login)
			.orElseThrow(() -> new ApiException(404, "GITHUB_README_NOT_FOUND",
				"Seu perfil do GitHub não tem um README público para importar."));
		String branch = StringUtils.hasText(readme.defaultBranch()) ? readme.defaultBranch() : "main";
		String html = markdown.render(readme.markdown(), login, login, branch);
		String repoUrl = StringUtils.hasText(readme.repoUrl()) ? readme.repoUrl()
			: "https://github.com/" + login + "/" + login;
		return new ReadmePreview(html, repoUrl);
	}

	@Transactional
	public ReadmePreview importReadme(String userId) {
		ReadmePreview preview = readmePreview(userId);
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Perfil não encontrado."));
		profile.updateBioHtml(preview.html(), clock.instant());
		profiles.save(profile);
		return preview;
	}

	@Transactional
	public ImportProjectsResult importProjects(String userId, List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			throw new ApiException(400, "NO_REPOSITORIES", "Selecione pelo menos um repositório.");
		}
		if (requested.size() > MAX_IMPORT) {
			throw new ApiException(400, "TOO_MANY_REPOSITORIES",
				"Importe no máximo " + MAX_IMPORT + " repositórios por vez.");
		}
		String login = login(userId);
		Map<String, GitHubUserRepository> available = new LinkedHashMap<>();
		for (GitHubUserRepository repo : github.repositories(login)) {
			available.put(repo.fullName().toLowerCase(Locale.ROOT), repo);
		}
		List<UserProject> existing = projects.of(userId);
		LinkedHashSet<String> existingUrls = new LinkedHashSet<>();
		for (UserProject project : existing) {
			if (StringUtils.hasText(project.repoUrl())) {
				existingUrls.add(project.repoUrl().toLowerCase(Locale.ROOT));
			}
		}

		int created = 0;
		boolean limitReached = false;
		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> invalid = new ArrayList<>();
		for (String fullName : new LinkedHashSet<>(requested)) {
			if (!StringUtils.hasText(fullName)) {
				continue;
			}
			String key = fullName.trim().toLowerCase(Locale.ROOT);
			GitHubUserRepository repo = available.get(key);
			if (repo == null) {
				invalid.add(fullName.trim());
				continue;
			}
			if (existingUrls.contains(repo.htmlUrl().toLowerCase(Locale.ROOT))) {
				skipped.add(repo.fullName());
				continue;
			}
			try {
				projects.create(userId, new UserProject(null, null, 0, title(repo), repo.description(), null, null,
					stack(repo), repo.htmlUrl(), null, List.of(), null, null, null));
				existingUrls.add(repo.htmlUrl().toLowerCase(Locale.ROOT));
				imported.add(repo.fullName());
				created++;
			}
			catch (ApiException failure) {
				if ("MAX_PROJECTS".equals(failure.code())) {
					limitReached = true;
					break;
				}
				throw failure;
			}
		}
		return new ImportProjectsResult(created, imported, skipped, invalid, limitReached);
	}

	private String login(String userId) {
		Optional<GitHubIdentity> identity = identities.findByUserId(userId);
		return identity.map(GitHubIdentity::githubLogin).orElseThrow(() -> new ApiException(403,
			"GITHUB_NOT_LINKED", "Vincule sua conta GitHub para importar seus projetos e seu README."));
	}

	private static String title(GitHubUserRepository repo) {
		return StringUtils.hasText(repo.name()) ? repo.name() : repo.fullName();
	}

	private static List<String> stack(GitHubUserRepository repo) {
		LinkedHashSet<String> items = new LinkedHashSet<>();
		for (String topic : repo.topics()) {
			addStack(items, topic);
		}
		addStack(items, repo.language());
		return List.copyOf(items);
	}

	private static void addStack(LinkedHashSet<String> items, String value) {
		if (items.size() >= MAX_STACK || !StringUtils.hasText(value)) {
			return;
		}
		String clean = value.trim();
		if (clean.length() > MAX_STACK_ITEM) {
			clean = clean.substring(0, MAX_STACK_ITEM);
		}
		items.add(clean);
	}

	public record ReadmePreview(String html, String repoUrl) {
	}

	public record ImportProjectsResult(int created, List<String> imported, List<String> skipped,
			List<String> invalid, boolean limitReached) {
	}
}
