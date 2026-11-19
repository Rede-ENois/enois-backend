package com.socialcoisa.rede.githubprofile.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Leitura pública do perfil do GitHub para importar projetos e o README. Usa o token fine-grained
 * de leitura (quando existe) só para elevar o limite; nunca pede nem guarda token do usuário.
 */
public interface GitHubProfileGateway {

	List<GitHubUserRepository> listUserRepositories(String login, int maxPages);

	Optional<GitHubProfileReadme> profileReadme(String login);

	/**
	 * As organizações que o login é membro (públicas na leitura anônima; com o token do serviço,
	 * as que o token vê). épico "Importar organizações do GitHub": a lista é o que o usuário
	 * escolhe importar como empresa.
	 */
	List<GitHubOrganization> listUserOrganizations(String login, int maxPages);

	/** Os repositórios públicos de uma organização (mesma leitura dos repos de usuário). */
	List<GitHubUserRepository> listOrganizationRepositoryDetails(String org, int maxPages);

	record GitHubUserRepository(String fullName, String name, String description, String htmlUrl, String homepage,
			String language, List<String> topics, int stars, int forks, boolean fork, boolean archived,
			Instant updatedAt, String defaultBranch) {
	}

	/** Organização do GitHub: login, nome de exibição e descrição pública. */
	record GitHubOrganization(String login, String name, String description, String htmlUrl) {
	}

	record GitHubProfileReadme(String markdown, String repoUrl, String defaultBranch) {
	}
}
