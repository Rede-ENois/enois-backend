package com.socialcoisa.rede.apiv1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O alias versionado da API ({@code /api/v1/...}).
 *
 * <p>O contrato do v1 é uma promessa: o caminho fixado responde o mesmo que o caminho solto. Estes
 * testes comparam as duas respostas lado a lado (mesmo status, mesmo corpo) em leitura pública,
 * leitura autenticada, escrita e erro — porque é justamente aí que um alias mal feito passa
 * despercebido (a escrita com idempotência, o limite de taxa do login e o envelope de erro).
 */
@SpringBootTest
@AutoConfigureMockMvc
class V1ApiAliasTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void leituraPublicaRespondeIgualNaVersao() throws Exception {
		MvcResult semVersao = mockMvc.perform(get("/stats/community")).andReturn();
		MvcResult comVersao = mockMvc.perform(get("/v1/stats/community")).andReturn();

		assertThat(comVersao.getResponse().getStatus()).isEqualTo(semVersao.getResponse().getStatus());
		assertThat(comVersao.getResponse().getContentAsString()).isEqualTo(semVersao.getResponse().getContentAsString());
	}

	@Test
	void leituraAutenticadaRespondeIgualNaVersao() throws Exception {
		TestAuthHelper.AuthSession session = auth.authenticate("v1-alias-leitura");

		MvcResult semVersao = mockMvc.perform(get("/me").header("X-Session-Token", session.token())).andReturn();
		MvcResult comVersao = mockMvc.perform(get("/v1/me").header("X-Session-Token", session.token())).andReturn();

		assertThat(comVersao.getResponse().getStatus()).isEqualTo(semVersao.getResponse().getStatus());
		assertThat(comVersao.getResponse().getContentAsString()).isEqualTo(semVersao.getResponse().getContentAsString());
	}

	@Test
	void erroRespondeComOMesmoEnvelope() throws Exception {
		MvcResult semVersao = mockMvc.perform(get("/feed/posts/post-que-nao-existe")).andReturn();
		MvcResult comVersao = mockMvc.perform(get("/v1/feed/posts/post-que-nao-existe")).andReturn();

		assertThat(semVersao.getResponse().getStatus()).isEqualTo(comVersao.getResponse().getStatus());
		// O corpo tem o instante da chamada (timestamp), entao a comparacao e campo a campo.
		var erroSemVersao = objectMapper.readTree(semVersao.getResponse().getContentAsString());
		var erroComVersao = objectMapper.readTree(comVersao.getResponse().getContentAsString());
		assertThat(erroComVersao.get("code").asText()).isEqualTo(erroSemVersao.get("code").asText());
		assertThat(erroComVersao.get("message").asText()).isEqualTo(erroSemVersao.get("message").asText());
		assertThat(erroComVersao.has("timestamp")).isTrue();
	}

	@Test
	void escritaComSessaoRespondeIgualNaVersao() throws Exception {
		TestAuthHelper.AuthSession session = auth.authenticate("v1-alias-escrita");
		String payload = objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "Post do alias v1."));

		MvcResult semVersao = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "v1-alias-escrita-sem")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)).andReturn();
		MvcResult comVersao = mockMvc.perform(post("/v1/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "v1-alias-escrita-com")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)).andReturn();

		assertThat(comVersao.getResponse().getStatus()).isEqualTo(semVersao.getResponse().getStatus());
		assertThat(comVersao.getResponse().getStatus()).isEqualTo(201);
		// As duas escritas criaram posts (ids diferentes, sao duas chamadas): o que tem de ser
		// igual e a FORMA da resposta.
		var corpoSemVersao = objectMapper.readTree(semVersao.getResponse().getContentAsString());
		var corpoComVersao = objectMapper.readTree(comVersao.getResponse().getContentAsString());
		List<String> camposSemVersao = new ArrayList<>(corpoSemVersao.propertyNames());
		List<String> camposComVersao = new ArrayList<>(corpoComVersao.propertyNames());
		assertThat(camposComVersao).containsExactlyInAnyOrderElementsOf(camposSemVersao);
	}

	@Test
	void escritaSemSessaoContinuaRecusadaNaVersao() throws Exception {
		// O alias nao abre porta: sem sessao, a escrita e recusada do mesmo jeito.
		mockMvc.perform(post("/v1/feed/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"sem sessao\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void indiceDaVersaoListaORoteadorDeVerdade() throws Exception {
		mockMvc.perform(get("/v1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value("v1"))
			.andExpect(jsonPath("$.base").value("/v1"))
			.andExpect(jsonPath("$.endpoints[?(@.path == '/stats/community')]").exists())
			.andExpect(jsonPath("$.endpoints[?(@.path == '/feed/posts/{postId}')]").exists());
	}

	@Test
	void oIndiceDiceQueNaoHaDocumentoOpenApi() throws Exception {
		// A API nao publica documento OpenAPI: o springdoc saiu do pom no lote de seguranca e nao
		// existe mais /openapi.json (quem pede recebe a resposta padronizada de caminho fora da
		// superficie, 200 sem corpo). O indice e o contrato legivel por maquina, entao ele precisa
		// dizer isso — senao quem integra procura um OpenAPI que nao vem.
		mockMvc.perform(get("/v1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.note", containsString("nao publica documento OpenAPI")))
			.andExpect(jsonPath("$.note", containsString("/developers")));
	}

	@Test
	void naoExisteDocumentoOpenApiNoBackend() throws Exception {
		// Tranca a decisao de seguranca: o caminho nao responde um documento (e nem um 404 de
		// aplicacao — a resposta de "caminho fora da superficie" e a mesma para qualquer caminho
		// desconhecido). Se um dia o springdoc voltar, este teste avisa.
		MvcResult pedido = mockMvc.perform(get("/openapi.json")).andReturn();
		assertThat(pedido.getResponse().getStatus()).isEqualTo(200);
		assertThat(pedido.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	void caminhoQueNaoExisteRespondeIgualNaVersao() throws Exception {
		MvcResult semVersao = mockMvc.perform(get("/nao-existe-mesmo")).andReturn();
		MvcResult comVersao = mockMvc.perform(get("/v1/nao-existe-mesmo")).andReturn();

		assertThat(comVersao.getResponse().getStatus()).isEqualTo(semVersao.getResponse().getStatus());
		// Caminho fora da superficie: a resposta padronizada (200, corpo em branco) vale para
		// a versao e para o caminho sem versao, igualzinhos.
		assertThat(comVersao.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void versaoNomeadaNaoEhVersao() throws Exception {
		// "/v1x" nao e a versao 1: o alias so reescreve "/v1" seguido de caminho, e o caminho
		// fora da superficie recebe a resposta padronizada, nunca os dados da rota real.
		mockMvc.perform(get("/v1x/stats/community"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}

	@Test
	void caminhoSemVersaoNaoEDeV1() throws Exception {
		// A rota sem versao continua exatamente como era: o alias nao muda nada nela.
		mockMvc.perform(get("/stats/community")).andExpect(status().isOk());
	}
}
