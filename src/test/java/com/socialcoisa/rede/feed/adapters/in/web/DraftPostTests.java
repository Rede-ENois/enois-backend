package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Rascunho do compositor (GET/PUT/DELETE /feed/draft): um por pessoa, atualizado a cada
 * salvamento, só do dono — ausência vem como `draft: null`, não como erro. Publicar e agendar
 * seguem pelos endpoints de sempre; aqui só se guarda onde a pessoa parou.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftPostTests {

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
	void rascunhoSalvoERestauradoComTodosOsCampos() throws Exception {
		var autora = auth.authenticate("rascunho_restaura");

		String corpo = """
			{"type":"POLL","content":"pesquisa da semana","topic":"TECNOLOGIA",
			"pollOptions":["cafe","cha","ambos"],"pollMultipleChoice":true,
			"pollEndsAt":"2030-01-01T10:00:00Z","mentionedUserIds":["usr_1"]}""";
		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON).content(corpo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.draft.type").value("POLL"));

		MvcResult result = mockMvc
			.perform(get("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode rascunho = objectMapper.readTree(result.getResponse().getContentAsString()).get("draft");

		assertThat(rascunho.get("content").asText()).isEqualTo("pesquisa da semana");
		assertThat(rascunho.get("topic").asText()).isEqualTo("TECNOLOGIA");
		assertThat(rascunho.get("pollOptions").size()).isEqualTo(3);
		assertThat(rascunho.get("pollOptions").get(0).asText()).isEqualTo("cafe");
		assertThat(rascunho.get("pollMultipleChoice").asBoolean()).isTrue();
		assertThat(rascunho.get("pollEndsAt").asText()).isEqualTo("2030-01-01T10:00:00Z");
		assertThat(rascunho.get("mentionedUserIds").size()).isEqualTo(1);
	}

	@Test
	void salvarDeNovoSubstituiOMesmoRascunho() throws Exception {
		var autora = auth.authenticate("rascunho_substitui");

		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"INSIGHT\",\"content\":\"primeira versao\",\"topic\":\"TECNOLOGIA\"}"))
			.andExpect(status().isOk());
		MvcResult primeiro = mockMvc
			.perform(get("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode original = objectMapper.readTree(primeiro.getResponse().getContentAsString()).get("draft");

		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"ARTICLE\",\"content\":\"novo titulo\",\"articleBody\":\"novo corpo\"}"))
			.andExpect(status().isOk());

		MvcResult result = mockMvc
			.perform(get("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode rascunho = objectMapper.readTree(result.getResponse().getContentAsString()).get("draft");

		// Mesma linha (id e criacao preservados), conteudo inteiro substituido.
		assertThat(rascunho.get("id").asText()).isEqualTo(original.get("id").asText());
		assertThat(rascunho.get("createdAt").asText()).isEqualTo(original.get("createdAt").asText());
		assertThat(rascunho.get("type").asText()).isEqualTo("ARTICLE");
		assertThat(rascunho.get("content").asText()).isEqualTo("novo titulo");
		assertThat(rascunho.get("articleBody").asText()).isEqualTo("novo corpo");
		assertThat(rascunho.get("topic").isNull()).isTrue();
	}

	@Test
	void rascunhoVazioERecusado() throws Exception {
		var autora = auth.authenticate("rascunho_vazio");

		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"INSIGHT\",\"content\":\"   \",\"topic\":\"TECNOLOGIA\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTEUDO_OBRIGATORIO"));

		// Enquete só com alternativas em branco também está vazia.
		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"POLL\",\"pollOptions\":[\"\",\"   \"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTEUDO_OBRIGATORIO"));
	}

	@Test
	void rascunhoExigeSessao() throws Exception {
		mockMvc.perform(put("/feed/draft").contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"INSIGHT\",\"content\":\"sem sessao\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/feed/draft")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/feed/draft")).andExpect(status().isUnauthorized());
	}

	@Test
	void rascunhoEPrivadoEDescarteIdempotente() throws Exception {
		var autora = auth.authenticate("rascunho_privado_dona");
		var outra = auth.authenticate("rascunho_privado_outra");

		mockMvc.perform(put("/feed/draft").header("X-Session-Token", autora.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"INSIGHT\",\"content\":\"segredo do rascunho\",\"topic\":\"TECNOLOGIA\"}"))
			.andExpect(status().isOk());

		// Outra pessoa não vê nem interfere: o dela é nulo, e o dela vazio não apaga o da autora.
		mockMvc.perform(get("/feed/draft").header("X-Session-Token", outra.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.draft").value(nullValue()));
		mockMvc.perform(delete("/feed/draft").header("X-Session-Token", outra.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.draft.content").value("segredo do rascunho"));

		// A dona descarta; descartar de novo (não há nada) continua ok.
		mockMvc.perform(delete("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/draft").header("X-Session-Token", autora.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.draft").value(nullValue()));
	}
}
