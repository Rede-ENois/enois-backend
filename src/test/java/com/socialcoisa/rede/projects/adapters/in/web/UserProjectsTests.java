package com.socialcoisa.rede.projects.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Cards de projeto do perfil: CRUD do dono, leitura pública por username, limites e
 * a sanitização do HTML (a mesma família da bio e da página).
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProjectsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request,
			TestAuthHelper.AuthSession session) {
		return request.header("X-User-Id", session.userId()).header("X-Session-Token", session.token());
	}

	private String body(String title) {
		return """
			{"title":"%s","stack":["Java","Spring Boot"],"repoUrl":"https://github.com/eu/projeto",
			"status":"em andamento","diary":[{"date":"2026-10-17","text":"primeiro commit"}],"html":null}
			""".formatted(title);
	}

	private String createProject(TestAuthHelper.AuthSession session, String title) throws Exception {
		var response = mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content(body(title)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	@Test
	void createListPublicAndDelete() throws Exception {
		var session = auth.authenticate("user_proj_a");
		String projectId = createProject(session, "Meu compilador");

		// Leitura pública por username: stack, status, diário e repo disponíveis.
		var me = objectMapper
			.readTree(mockMvc.perform(withSession(get("/me"), session)).andReturn().getResponse()
				.getContentAsString());
		var resposta = mockMvc.perform(get("/users/" + me.get("username").asText() + "/projects"))
			.andReturn();
		System.out.println("DEBUG-404 status=" + resposta.getResponse().getStatus() + " body="
			+ resposta.getResponse().getContentAsString() + " username=" + me.get("username").asText());
		mockMvc.perform(get("/users/" + me.get("username").asText() + "/projects"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].title").value("Meu compilador"))
			.andExpect(jsonPath("$[0].stack.length()").value(2))
			.andExpect(jsonPath("$[0].diary.length()").value(1));

		mockMvc.perform(withSession(put("/me/projects/" + projectId), session).contentType(MediaType.APPLICATION_JSON)
			.content(body("Meu compilador v2")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Meu compilador v2"));

		mockMvc.perform(withSession(delete("/me/projects/" + projectId), session))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/users/" + me.get("username").asText() + "/projects"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void projectHtmlIsSanitized() throws Exception {
		var session = auth.authenticate("user_proj_b");

		var response = mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"Card HTML","stack":[],"repoUrl":null,"status":null,"diary":[],
				"html":"<h2 style=\\"color:red\\">Meu projeto</h2><script>alert(1)</script>"}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.html").isString())
			.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain("<script");
		assertThat(response).contains("Meu projeto");
	}

	@Test
	void refusesTooLongTitleAndNonHttpsRepo() throws Exception {
		var session = auth.authenticate("user_proj_c");

		String longoVariado = "projeto-de-teste-".repeat(8) + "x"; // 137 chars, sem repetição pura
		mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"" + longoVariado + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIELD_TOO_LONG"));

		mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"X\",\"repoUrl\":\"http://inseguro.com/x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REPO_URL"));
	}

	@Test
	void onlyTheOwnerCanEdit() throws Exception {
		var owner = auth.authenticate("user_proj_owner");
		var stranger = auth.authenticate("user_proj_stranger");
		String projectId = createProject(owner, "Do dono");

		// Outro usuário: 404 (não revela que existe).
		mockMvc.perform(withSession(put("/me/projects/" + projectId), stranger)
			.contentType(MediaType.APPLICATION_JSON).content(body("Hackeado")))
			.andExpect(status().isNotFound());
	}

	@Test
	void largeProjectHtmlAllowed() throws Exception {
		var session = auth.authenticate("user_proj_large_html");

		// HTML grande com ~9.000 caracteres (acima de 5.000, dentro do teto de 20.000)
		String largeHtml = "<div><h2>Projeto Grande</h2>"
				+ "<p style=\"color:#fff\">" + "Bloco de conteudo explicativo detalhado sobre a plataforma. ".repeat(150) + "</p></div>";
		assertThat(largeHtml.length()).isGreaterThan(5000);
		assertThat(largeHtml.length()).isLessThan(20000);

		var payload = objectMapper.createObjectNode()
			.put("title", "NeoToons")
			.put("html", largeHtml);
		payload.putArray("stack");
		payload.putArray("diary");

		var response = mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.title").value("NeoToons"))
			.andReturn().getResponse().getContentAsString();

		assertThat(response).contains("Projeto Grande");
	}

	@Test
	void projectBannerAndIconRoundTripAndRejectNonHttps() throws Exception {
		var session = auth.authenticate("user_proj_banner");

		var response = mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"Com banner","description":"card com imagem","banner":"https://images.example/banner.png",
				"icon":"https://images.example/icon.png","stack":[],"diary":[]}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.banner").value("https://images.example/banner.png"))
			.andExpect(jsonPath("$.icon").value("https://images.example/icon.png"))
			.andReturn().getResponse().getContentAsString();
		String projectId = objectMapper.readTree(response).get("id").asText();

		// Leitura pública por username: as imagens saem para quem vê o perfil.
		var me = objectMapper
			.readTree(mockMvc.perform(withSession(get("/me"), session)).andReturn().getResponse()
				.getContentAsString());
		mockMvc.perform(get("/users/" + me.get("username").asText() + "/projects"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + projectId + "')].banner")
				.value("https://images.example/banner.png"))
			.andExpect(jsonPath("$[?(@.id == '" + projectId + "')].icon")
				.value("https://images.example/icon.png"));

		// Sem https, banner e ícone recusam (o mesmo guarda do repositório).
		mockMvc.perform(withSession(put("/me/projects/" + projectId), session).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"Com banner","banner":"http://inseguro.example/banner.png","stack":[],"diary":[]}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BANNER"));
	}

	@Test
	void projectHtmlKeepsUnicodeOutsideLatin1() throws Exception {
		var session = auth.authenticate("user_proj_unicode_html");
		String html = "<p>Conhecer a Flecha →</p>";

		var payload = objectMapper.createObjectNode()
			.put("title", "Flecha")
			.put("html", html);
		payload.putArray("stack");
		payload.putArray("diary");

		var response = mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(response).contains("→");
	}

	@Test
	void diaryEntryAcceptsTheWholeLinkedInDescription() throws Exception {
		var session = auth.authenticate("user_proj_diary_2000");

		// A descrição de uma experiência do LinkedIn tem até 2.000 caracteres e vira a
		// primeira entrada do diário na importação: o limite antigo (400) derrubava o import.
		String description = ("Entrega de plataforma com foco em desempenho, integrações e suporte "
			+ "a times de produto em todos os ciclos. ").repeat(30).substring(0, 2000);
		var payload = objectMapper.createObjectNode().put("title", "Projeto importado");
		payload.putArray("stack");
		payload.putArray("diary").addObject()
			.put("date", "2026-01-01")
			.put("text", description);

		mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.diary[0].text", org.hamcrest.Matchers.hasLength(2000)));

		var tooLong = objectMapper.createObjectNode().put("title", "Projeto importado 2");
		tooLong.putArray("stack");
		tooLong.putArray("diary").addObject()
			.put("date", "2026-01-01")
			.put("text", description + "x");

		mockMvc.perform(withSession(post("/me/projects"), session).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(tooLong)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIELD_TOO_LONG"));
	}

	@Test
	void requiresSessionForWrites() throws Exception {
		mockMvc.perform(post("/me/projects").contentType(MediaType.APPLICATION_JSON).content(body("X")))
			.andExpect(status().isUnauthorized());
	}
}
