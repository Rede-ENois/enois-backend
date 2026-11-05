package com.socialcoisa.rede.githubprofile.application;

import java.util.List;
import java.util.Optional;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubProfileReadme;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubUserRepository;

/**
 * Leitura do GitHub com cache curto por login. A lista de repositórios e o README são dados públicos
 * e cada consulta passa pela rede (a listagem chega a 3 páginas): abrir o diálogo de importação e
 * importar em seguida não podem pagar a mesma ida ao GitHub duas vezes.
 *
 * <p>Fica numa classe à parte porque o cache do Spring só vale para chamada vinda de outro bean.
 * Falha e README ausente não entram no cache — quem acabou de criar o repositório do perfil não
 * espera o prazo para ver o README.</p>
 */
@Component
public class CachedGitHubProfileReader {

	private static final int MAX_PAGES = 3;

	private final GitHubProfileGateway gateway;

	public CachedGitHubProfileReader(GitHubProfileGateway gateway) {
		this.gateway = gateway;
	}

	@Cacheable(cacheNames = "githubRepositories", key = "#login.toLowerCase()")
	public List<GitHubUserRepository> repositories(String login) {
		return gateway.listUserRepositories(login, MAX_PAGES);
	}

	@Cacheable(cacheNames = "githubReadme", key = "#login.toLowerCase()", unless = "#result == null")
	public Optional<GitHubProfileReadme> profileReadme(String login) {
		return gateway.profileReadme(login);
	}
}
