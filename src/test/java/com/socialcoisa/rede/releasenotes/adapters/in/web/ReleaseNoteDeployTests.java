package com.socialcoisa.rede.releasenotes.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O disparo automatico das "Novidades" pelo deploy de producao (issue #622).
 *
 * O CI nao tem sessao de ninguem: a credencial da rota interna e o token de servico
 * {@code X-Deploy-Token}, fail-closed como o webhook do GitHub. O mesmo deploy nao publica duas
 * vezes (SHA repetido e 409): uma reexecucao do workflow nao pode reabrir o popup para todo
 * mundo. A prova do 503 (sem token configurado) vive em {@link ReleaseNoteDeployNotConfiguredTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseNoteDeployTests {

	private static final String TOKEN = "token-de-deploy-de-teste";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "deploy_admin@test.com");
		registry.add("rede.release-notes.deploy-token", () -> TOKEN);
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from release_note");
		jdbc.update("delete from deploy_publication");
	}

	@Test
	void tokenErradoRecusaENadaEntraNoAr() throws Exception {
		mockMvc.perform(publish("token-errado", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			item("Novidade", "Primeira publicacao via deploy")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_DEPLOY_TOKEN"));
		mockMvc.perform(publish(null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			item("Novidade", "Primeira publicacao via deploy")))
			.andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("select count(*) from release_note", Integer.class)).isZero();
	}

	@Test
	void publishValidoApareceEmNovidadesAtualEUltimoRespondeOSha() throws Exception {
		String sha = "b".repeat(40);
		mockMvc.perform(publish(TOKEN, sha, item("Enquetes no feed", "Agora da para criar enquete.")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.published").value(true))
			.andExpect(jsonPath("$.publishedAt").isNotEmpty());

		mockMvc.perform(get("/novidades/atual"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Enquetes no feed"));

		String ultimo = mockMvc
			.perform(get("/internal/novidades-deploy/ultimo").header("X-Deploy-Token", TOKEN))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(ultimo).get("commitSha").asString()).isEqualTo(sha);

		// Sem token, a rota de leitura interna também recusa: mesma credencial do POST.
		mockMvc.perform(get("/internal/novidades-deploy/ultimo"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void mesmoShaDuasVezesRecusaEConteudoAnteriorPermanece() throws Exception {
		String sha = "c".repeat(40);
		String primeira = mockMvc.perform(publish(TOKEN, sha, item("Versao 1", "Primeira")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		// A resposta do POST e o recibo do deploy; o conteudo em si esta em /novidades/atual.
		assertThat(primeira).contains("\"published\":true");
		String noAr = mockMvc.perform(get("/novidades/atual"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(noAr).contains("Versao 1");

		// Reexecucao do workflow: mesmo SHA, itens diferentes. Recusa e nao toca no que esta no ar.
		mockMvc.perform(publish(TOKEN, sha, item("Versao 2", "Segunda")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DEPLOY_ALREADY_PUBLISHED"));

		String atual = mockMvc.perform(get("/novidades/atual"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(atual).isEqualTo(noAr);
		assertThat(atual).contains("Versao 1").doesNotContain("Versao 2");
		assertThat(jdbc.queryForObject("select count(*) from deploy_publication", Integer.class)).isEqualTo(1);
	}

	@Test
	void itemInvalidoRecusaComOsCodigosDoServico() throws Exception {
		// O SHA vem antes dos itens no service: sem ele, a recusa e do SHA, nao dos itens.
		expectCode(publishRaw(TOKEN, "", Map.of("items", List.of(item("T", "D")))), "INVALID_COMMIT_SHA");
		expectCode(publishRaw(TOKEN, "   ", Map.of("items", List.of(item("T", "D")))), "INVALID_COMMIT_SHA");
		expectCode(publishRaw(TOKEN, "d".repeat(40), Map.of()), "INVALID_RELEASE_ITEMS");
		expectCode(publishRaw(TOKEN, "d".repeat(40), Map.of("items", List.of())), "INVALID_RELEASE_ITEMS");
		expectCode(publishRaw(TOKEN, "d".repeat(40), Map.of("items", List.of(item("   ", "D")))),
			"RELEASE_ITEM_TITLE_REQUIRED");
		// O publish recusado NAO grava o SHA: o proximo deploy (com itens validos) publica.
		assertThat(jdbc.queryForObject("select count(*) from deploy_publication", Integer.class)).isZero();
	}

	@Test
	void contaComumContinuaProibidaDePublicar() throws Exception {
		var person = auth.authenticate("deploy_plain_person");
		mockMvc.perform(put("/admin/novidades")
				.header("X-Session-Token", person.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("items", List.of(item("T", "D"))))))
			.andExpect(status().isForbidden());
	}

	private static Map<String, String> item(String title, String description) {
		return Map.of("title", title, "description", description);
	}

	private RequestBuilder publish(String token, String sha, Map<String, String> item) {
		return publishRaw(token, sha, Map.of("commitSha", sha, "items", List.of(item)));
	}

	private RequestBuilder publishRaw(String token, String sha, Map<String, ?> body) {
		var builder = post("/internal/novidades-deploy");
		if (token != null) {
			builder = builder.header("X-Deploy-Token", token);
		}
		// O commitSha vai no corpo (nao e segredo, e a chave de idempotencia que o CI declara).
		var completo = new java.util.LinkedHashMap<String, Object>();
		completo.put("commitSha", sha);
		completo.putAll(body);
		return builder.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(completo));
	}

	private void expectCode(RequestBuilder request, String code) throws Exception {
		mockMvc.perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(code));
	}
}
