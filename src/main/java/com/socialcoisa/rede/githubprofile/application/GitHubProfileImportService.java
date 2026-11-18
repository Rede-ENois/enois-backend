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
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubOrganization;
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
	private final com.socialcoisa.rede.companies.application.CompanyService companies;

	public GitHubProfileImportService(CachedGitHubProfileReader github, GitHubIdentityRepository identities,
			UserProfileRepository profiles, ProjectsService projects, MarkdownBioRenderer markdown, Clock clock,
			com.socialcoisa.rede.companies.application.CompanyService companies) {
		this.github = github;
		this.identities = identities;
		this.profiles = profiles;
		this.projects = projects;
		this.markdown = markdown;
		this.clock = clock;
		this.companies = companies;
	}

	public List<GitHubUserRepository> repositories(String userId) {
		return github.repositories(login(userId));
	}

	/**
	 * As organizações do GitHub que a pessoa é membro: a lista para escolher o que importar como
	 * empresa (épico "Importar organizações do GitHub"). Só o que é público/token vê — repos
	 * privados nunca saem do GitHub para a rede por aqui.
	 */
	public List<GitHubOrganization> organizations(String userId) {
		return github.organizations(login(userId));
	}

	/**
	 * Repos públicos de uma organização do GitHub, para a pessoa escolher quais importar como
	 * projetos (mesma régua dos repos próprios: posse vira seleção, teto e validação idênticos).
	 */
	public List<GitHubUserRepository> organizationRepositories(String userId, String org) {
		return github.organizationRepositoryDetails(login(userId), org);
	}

	/**
	 * Importa uma organização como EMPRESA na rede (nome, descrição, site apontando para o
	 * github.com/org) e os repos escolhidos como projetos do perfil de quem importou, com a
	 * origem marcada na descrição. Idempotente pela URL do repo (a mesma chave dos repos
	 * próprios) e pelo slug da empresa.
	 */
	@Transactional
	public ImportOrganizationResult importOrganization(String userId, String org, List<String> requested) {
		String safeOrg = org == null ? "" : org.trim().toLowerCase(Locale.ROOT);
		if (!StringUtils.hasText(safeOrg)) {
			throw new ApiException(400, "ORG_OBRIGATORIA", "Escolha a organização para importar.");
		}
		// A pessoa precisa ser MEMBRO da org que está importando (é o dado público que autoriza).
		boolean membro = github.organizations(login(userId)).stream()
			.anyMatch(o -> o.login().toLowerCase(Locale.ROOT).equals(safeOrg));
		if (!membro) {
			throw new ApiException(403, "ORG_NAO_MEMBRO", "Você não é membro dessa organização no GitHub.");
		}

		// Idempotência por DONO: se eu já importei essa org, a minha empresa é reusada. A empresa
		// da org de OUTRA pessoa não me impede de ter a minha (a base compartilhada dos testes e
		// a rede real têm muitas pessoas da mesma org importando).
		com.socialcoisa.rede.companies.domain.Company empresa = companies.memberOf(userId).stream()
			.filter(c -> c.website() != null
				&& c.website().toLowerCase(Locale.ROOT).endsWith("github.com/" + safeOrg))
			.findFirst()
			.orElse(null);
		if (empresa == null) {
			var detalhes = github.organizationRepositoryDetails(login(userId), safeOrg);
			String descricao = detalhes.stream().map(GitHubUserRepository::description)
				.filter(StringUtils::hasText).findFirst()
				.orElse("Organização importada do GitHub.");
			empresa = companies.create(userId, orgDisplayName(safeOrg), descricao, "Tecnologia", null, null,
				"https://github.com/" + safeOrg);
		}

		// A importação compara os pedidos contra os repos DA ORG (não os do usuário): é o catálogo
		// que a pessoa acabou de ver na lista da organização. A validação de seleção/teto/posse é
		// a mesma do import de repos próprios.
		int created = importFrom(userId, requested,
			github.organizationRepositoryDetails(login(userId), safeOrg)).created();
		return new ImportOrganizationResult(empresa.slug(), empresa.name(), created);
	}

	/** Nome de exibição da empresa: o nome público da org, ou o login quando não há nome. */
	private static String orgDisplayName(String org) {
		return org;
	}

	public record ImportOrganizationResult(String slug, String name, int projectsCreated) {
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
		return importFrom(userId, requested, github.repositories(login(userId)));
	}

	/** Importação com catálogo alternativo (os repos de uma organização, no import de orgs). */
	private ImportProjectsResult importFrom(String userId, List<String> requested,
			List<GitHubUserRepository> catalogo) {
		if (requested == null || requested.isEmpty()) {
			throw new ApiException(400, "NO_REPOSITORIES", "Selecione pelo menos um repositório.");
		}
		if (requested.size() > MAX_IMPORT) {
			throw new ApiException(400, "TOO_MANY_REPOSITORIES",
				"Importe no máximo " + MAX_IMPORT + " repositórios por vez.");
		}
		Map<String, GitHubUserRepository> available = new LinkedHashMap<>();
		for (GitHubUserRepository repo : catalogo) {
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
