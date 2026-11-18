package com.socialcoisa.rede.githubprofile.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.githubprofile.application.GitHubProfileImportService;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubUserRepository;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Importação do GitHub para o perfil: lista os repositórios públicos do vínculo, converte os
 * escolhidos em cards de projeto e o README do perfil em bio HTML. Tudo exige sessão e usa o
 * handle verificado — o corpo nunca escolhe a conta.
 */
@RestController
class GitHubProfileImportController {

	private final GitHubProfileImportService service;
	private final CurrentUserResolver currentUserResolver;

	GitHubProfileImportController(GitHubProfileImportService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/me/github/repos")
	List<RepoView> repositories(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.repositories(user.id().value()).stream().map(RepoView::from).toList();
	}

	@GetMapping("/me/github/readme")
	ReadmeView readme(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return ReadmeView.from(service.readmePreview(user.id().value()));
	}

	/** As organizações do GitHub de quem está logado (a lista para escolher o que importar). */
	@GetMapping("/me/github/orgs")
	List<?> organizations(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.organizations(user.id().value());
	}

	/** Os repositórios públicos de uma organização (a lista para escolher os projetos). */
	@GetMapping("/me/github/orgs/{org}/repos")
	List<?> organizationRepositories(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String org) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.organizationRepositories(user.id().value(), org);
	}

	/** Importa a organização como empresa e os repos escolhidos como projetos. */
	@PostMapping("/me/github/import/orgs/{org}")
	Map<String, Object> importOrganization(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String org, @RequestBody orgRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var resultado = service.importOrganization(user.id().value(), org, request.repos());
		return Map.of("slug", resultado.slug(), "name", resultado.name(), "created", resultado.projectsCreated());
	}

	record orgRequest(java.util.List<String> repos) {
	}

	@PostMapping("/me/github/import/projects")
	GitHubProfileImportService.ImportProjectsResult importProjects(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody ImportProjectsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.importProjects(user.id().value(), request == null ? null : request.repos());
	}

	@PostMapping("/me/github/import/readme")
	ReadmeView importReadme(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return ReadmeView.from(service.importReadme(user.id().value()));
	}

	record ImportProjectsRequest(List<String> repos) {
	}

	record ReadmeView(String html, String repoUrl) {
		static ReadmeView from(GitHubProfileImportService.ReadmePreview preview) {
			return new ReadmeView(preview.html(), preview.repoUrl());
		}
	}

	record RepoView(String fullName, String name, String description, String url, String homepage,
			String language, List<String> topics, int stars, int forks, boolean fork, boolean archived,
			Instant updatedAt, String defaultBranch) {
		static RepoView from(GitHubUserRepository repo) {
			return new RepoView(repo.fullName(), repo.name(), repo.description(), repo.htmlUrl(), repo.homepage(),
				repo.language(), repo.topics(), repo.stars(), repo.forks(), repo.fork(), repo.archived(),
				repo.updatedAt(), repo.defaultBranch());
		}
	}
}