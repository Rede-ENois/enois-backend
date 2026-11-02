package com.socialcoisa.rede.githubprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubProfileReadme;
import com.socialcoisa.rede.githubprofile.application.port.out.GitHubProfileGateway.GitHubUserRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.projects.application.ProjectsService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Importação de repositórios e README do GitHub para o próprio perfil. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GitHubProfileImportTests.FakeGatewayConfig.class)
class GitHubProfileImportTests {

	private static final AtomicReference<List<GitHubUserRepository>> REPOS = new AtomicReference<>(List.of());
	private static final AtomicReference<GitHubProfileReadme> README = new AtomicReference<>();
	/** Idas ao GitHub por login: cada teste usa um login próprio, então a contagem é só dele. */
	private static final Map<String, AtomicInteger> LIST_CALLS = new ConcurrentHashMap<>();
	private static final Map<String, AtomicInteger> README_CALLS = new ConcurrentHashMap<>();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private GitHubIdentityService identities;

	@Autowired
	private ProjectsService projects;

	@Autowired
	private UserProfileRepository profiles;

	@TestConfiguration
	static class FakeGatewayConfig {
		@Bean
		@Primary
		GitHubProfileGateway fakeProfileGateway() {
			return new FakeGateway();
		}
	}

	static class FakeGateway implements GitHubProfileGateway {
		@Override
		public List<GitHubUserRepository> listUserRepositories(String login, int maxPages) {
			LIST_CALLS.computeIfAbsent(login, key -> new AtomicInteger()).incrementAndGet();
			return REPOS.get();
		}

		@Override
		public Optional<GitHubProfileReadme> profileReadme(String login) {
			README_CALLS.computeIfAbsent(login, key -> new AtomicInteger()).incrementAndGet();
			return Optional.ofNullable(README.get());
		}
	}

	private static int listCalls(String login) {
		return LIST_CALLS.getOrDefault(login, new AtomicInteger()).get();
	}

	private static int readmeCalls(String login) {
		return README_CALLS.getOrDefault(login, new AtomicInteger()).get();
	}

	@Test
	void importsSelectedRepositoriesIdempotently() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String login = "import-dev-" + suffix;
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("import_" + suffix);
		identities.remember(user.userId(), "gh-" + login, login);
		REPOS.set(List.of(
			repo(login, "alpha", "Projeto Alpha", "https://github.com/" + login + "/alpha", "Java",
				List.of("java", "spring"), 12, 3),
			repo(login, "beta", "Projeto Beta", "https://github.com/" + login + "/beta", "TypeScript",
				List.of("react"), 5, 1)));

		mockMvc.perform(get("/me/github/repos").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].fullName").value(login + "/alpha"));

		mockMvc.perform(post("/me/github/import/projects")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("repos",
					List.of(login + "/alpha", login + "/beta", "outro/repo")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(2))
			.andExpect(jsonPath("$.invalid[0]").value("outro/repo"));

		var saved = projects.of(user.userId());
		assertThat(saved).hasSize(2);
		var alpha = saved.stream().filter(project -> project.title().equals("alpha")).findFirst().orElseThrow();
		assertThat(alpha.description()).isEqualTo("Projeto Alpha");
		assertThat(alpha.repoUrl()).isEqualTo("https://github.com/" + login + "/alpha");
		assertThat(alpha.stack()).contains("java", "spring", "Java");

		mockMvc.perform(post("/me/github/import/projects")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("repos",
					List.of(login + "/alpha", login + "/beta")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(0))
			.andExpect(jsonPath("$.skipped.length()").value(2));
		assertThat(projects.of(user.userId())).hasSize(2);
	}

	@Test
	void readmeBecomesSanitizedBioWithAbsoluteLinks() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String login = "readme-dev-" + suffix;
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("readme_" + suffix);
		identities.remember(user.userId(), "gh-" + login, login);
		String markdown = """
			# Olá, eu sou a pessoa

			**negrito** e [guia](docs/guia.md)

			![logo](./logo.png)

			| a | b |
			|---|---|
			| 1 | 2 |

			<script>alert(1)</script>
			""";
		README.set(new GitHubProfileReadme(markdown, "https://github.com/" + login + "/" + login, "main"));

		MvcResult preview = mockMvc.perform(get("/me/github/readme")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn();
		String html = objectMapper.readTree(preview.getResponse().getContentAsString()).path("html").asText();
		assertThat(html).contains("<strong>negrito</strong>");
		assertThat(html).contains("https://raw.githubusercontent.com/" + login + "/" + login + "/main/logo.png");
		assertThat(html).contains("https://github.com/" + login + "/" + login + "/blob/main/docs/guia.md");
		assertThat(html).contains("<table>");
		assertThat(html).doesNotContain("<script");

		mockMvc.perform(post("/me/github/import/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());

		String username = profiles.findById(user.userId()).orElseThrow().username();
		MvcResult profile = mockMvc.perform(get("/users/" + username))
			.andExpect(status().isOk())
			.andReturn();
		String bio = objectMapper.readTree(profile.getResponse().getContentAsString()).path("bioHtml").asText();
		assertThat(bio).contains("negrito");
		assertThat(bio).contains("https://raw.githubusercontent.com/" + login + "/" + login + "/main/logo.png");
	}

	@Test
	void listingAndImportingShareOneTripToGitHub() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String login = "cache-dev-" + suffix;
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("cache_" + suffix);
		identities.remember(user.userId(), "gh-" + login, login);
		REPOS.set(List.of(repo(login, "alpha", "Projeto Alpha", "https://github.com/" + login + "/alpha", "Java",
			List.of("java"), 1, 0)));

		mockMvc.perform(get("/me/github/repos").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/me/github/repos").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/github/import/projects")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("repos", List.of(login + "/alpha")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(1));

		assertThat(listCalls(login)).isEqualTo(1);
	}

	@Test
	void readmeIsCachedButMissingReadmeIsNot() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String login = "readmecache-dev-" + suffix;
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("readmecache_" + suffix);
		identities.remember(user.userId(), "gh-" + login, login);

		README.set(null);
		mockMvc.perform(get("/me/github/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound());
		README.set(new GitHubProfileReadme("# Oi", "https://github.com/" + login + "/" + login, "main"));
		mockMvc.perform(get("/me/github/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/me/github/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());

		assertThat(readmeCalls(login)).isEqualTo(2);
	}

	@Test
	void unlinkedUserCannotImport() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("nolink_" + suffix);

		mockMvc.perform(get("/me/github/repos").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GITHUB_NOT_LINKED"));

		mockMvc.perform(post("/me/github/import/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GITHUB_NOT_LINKED"));
	}

	@Test
	void missingReadmeReturnsClearError() throws Exception {
		String suffix = String.valueOf(System.nanoTime());
		String login = "noreadme-dev-" + suffix;
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("noreadme_" + suffix);
		identities.remember(user.userId(), "gh-" + login, login);
		README.set(null);

		mockMvc.perform(get("/me/github/readme").header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GITHUB_README_NOT_FOUND"));
	}

	private static GitHubUserRepository repo(String login, String name, String description, String url,
			String language, List<String> topics, int stars, int forks) {
		return new GitHubUserRepository(login + "/" + name, name, description, url,
			"https://" + name + ".example.com", language, topics, stars, forks, false, false, Instant.now(), "main");
	}
}