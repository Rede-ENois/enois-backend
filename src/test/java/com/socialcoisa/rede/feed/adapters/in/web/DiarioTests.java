package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
 * Diario de atividade do perfil (GET /users/{username}/diario): linha do tempo unica que une
 * publicacoes, reposts e (so para o dono) comentarios — mesma regra de privacidade da aba de
 * respostas. Repost de post apagado e comentario apagado saem da linha; a paginacao segue o
 * cursor de data igual as demais listas do perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiarioTests {

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
	void diarioDoDonoUnePublicacoesComentariosERepostsEmOrdemCronologica() throws Exception {
		var dono = auth.authenticate("diario_dono_ordem");
		var terceiro = auth.authenticate("diario_terceiro_ordem");
		String donoUsername = usernameOf(dono);

		String postAntigo = publish(dono, "primeira publicacao do diario", "diario-ordem-1");
		// created_at tem precisao de segundos: a pausa garante ordem deterministica na linha.
		Thread.sleep(1100);
		String postDoTerceiro = publish(terceiro, "post original do terceiro", "diario-ordem-2");
		Thread.sleep(1100);
		String comentarioId = comment(dono, postDoTerceiro, "opiniao no post do terceiro");
		Thread.sleep(1100);
		repost(dono, postDoTerceiro);
		Thread.sleep(1100);
		String postNovo = publish(dono, "publicacao mais recente", "diario-ordem-3");

		MvcResult result = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode itens = objectMapper.readTree(result.getResponse().getContentAsString()).get("itens");

		assertThat(itens).hasSize(4);
		assertThat(itens.get(0).get("tipo").asText()).isEqualTo("PUBLICACAO");
		assertThat(itens.get(0).get("postId").asText()).isEqualTo(postNovo);
		// Publicacao nao tem contexto; o trecho e do proprio conteudo.
		assertThat(itens.get(0).get("contexto").isNull()).isTrue();
		assertThat(itens.get(0).get("trecho").asText()).isEqualTo("publicacao mais recente");

		// Repost aponta pro post original e nomeia o autor dele.
		assertThat(itens.get(1).get("tipo").asText()).isEqualTo("REPOST");
		assertThat(itens.get(1).get("postId").asText()).isEqualTo(postDoTerceiro);
		assertThat(itens.get(1).get("contexto").get("trecho").asText()).isEqualTo("post original do terceiro");
		assertThat(itens.get(1).get("contexto").get("autorUsername").asText()).isNotBlank();
		assertThat(itens.get(1).get("contexto").get("autorNome").asText()).isNotBlank();

		// Comentario guarda o trecho proprio e o contexto do post pai.
		assertThat(itens.get(2).get("tipo").asText()).isEqualTo("COMENTARIO");
		assertThat(itens.get(2).get("postId").asText()).isEqualTo(postDoTerceiro);
		assertThat(itens.get(2).get("trecho").asText()).isEqualTo("opiniao no post do terceiro");
		assertThat(itens.get(2).get("contexto").get("trecho").asText()).isEqualTo("post original do terceiro");

		assertThat(itens.get(3).get("tipo").asText()).isEqualTo("PUBLICACAO");
		assertThat(itens.get(3).get("postId").asText()).isEqualTo(postAntigo);
	}

	@Test
	void diarioDoVisitanteOmiteComentariosMasMostraPublicacoesEReposts() throws Exception {
		var dono = auth.authenticate("diario_dono_visita");
		var visitante = auth.authenticate("diario_visitante_visita");
		var terceiro = auth.authenticate("diario_terceiro_visita");
		String donoUsername = usernameOf(dono);

		publish(dono, "publicacao aberta a visitantes", "diario-vis-1");
		String postDoTerceiro = publish(terceiro, "original repostado pelo dono", "diario-vis-2");
		comment(dono, postDoTerceiro, "comentario que visitante nao ve");
		repost(dono, postDoTerceiro);

		MvcResult result = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", visitante.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode itens = objectMapper.readTree(result.getResponse().getContentAsString()).get("itens");

		List<String> tipos = new java.util.ArrayList<>();
		itens.forEach(item -> tipos.add(item.get("tipo").asText()));
		// Comentario e so do dono: na linha do visitante sobram publicacao e repost.
		assertThat(tipos).containsExactlyInAnyOrder("PUBLICACAO", "REPOST");
	}

	@Test
	void diarioAnonimoTambemOmiteComentarios() throws Exception {
		var dono = auth.authenticate("diario_dono_anon");
		var terceiro = auth.authenticate("diario_terceiro_anon");
		String donoUsername = usernameOf(dono);

		publish(dono, "publicacao publica", "diario-anon-1");
		String postDoTerceiro = publish(terceiro, "original do terceiro anon", "diario-anon-2");
		comment(dono, postDoTerceiro, "comentario privado");
		// created_at tem precisao de segundos: sem a pausa, publicacao e repost empatam na ordem.
		Thread.sleep(1100);
		repost(dono, postDoTerceiro);

		mockMvc.perform(get("/users/" + donoUsername + "/diario"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itens.length()").value(2))
			.andExpect(jsonPath("$.itens[0].tipo").value("REPOST"))
			.andExpect(jsonPath("$.itens[1].tipo").value("PUBLICACAO"));
	}

	@Test
	void diarioPaginaPeloCursorDeData() throws Exception {
		var dono = auth.authenticate("diario_dono_pagina");
		String donoUsername = usernameOf(dono);

		String primeiro = publish(dono, "publicacao mais antiga", "diario-pag-1");
		Thread.sleep(1100);
		String meio = publish(dono, "publicacao do meio", "diario-pag-2");
		Thread.sleep(1100);
		String ultimo = publish(dono, "publicacao mais recente", "diario-pag-3");

		MvcResult primeira = mockMvc.perform(get("/users/" + donoUsername + "/diario?limit=2")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode pagina = objectMapper.readTree(primeira.getResponse().getContentAsString());
		assertThat(pagina.get("itens")).hasSize(2);
		assertThat(pagina.get("itens").get(0).get("postId").asText()).isEqualTo(ultimo);
		assertThat(pagina.get("itens").get(1).get("postId").asText()).isEqualTo(meio);
		String cursor = pagina.get("nextCursor").asText();
		assertThat(cursor).isNotBlank();

		MvcResult segunda = mockMvc.perform(get("/users/" + donoUsername + "/diario?limit=2&cursor=" + cursor)
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode resto = objectMapper.readTree(segunda.getResponse().getContentAsString());
		assertThat(resto.get("itens")).hasSize(1);
		assertThat(resto.get("itens").get(0).get("postId").asText()).isEqualTo(primeiro);
	}

	@Test
	void comentarioApagadoSaiDoDiario() throws Exception {
		var dono = auth.authenticate("diario_dono_apaga");
		var terceiro = auth.authenticate("diario_terceiro_apaga");
		String donoUsername = usernameOf(dono);

		String postDoTerceiro = publish(terceiro, "post que recebeu comentario", "diario-del-1");
		String comentarioId = comment(dono, postDoTerceiro, "comentario que vou apagar");

		MvcResult antes = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(antes.getResponse().getContentAsString()).contains("comentario que vou apagar");

		mockMvc.perform(delete("/feed/posts/" + postDoTerceiro + "/comments/" + comentarioId)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "diario-del-c1"))
			.andExpect(status().isOk());

		MvcResult depois = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(depois.getResponse().getContentAsString()).doesNotContain("comentario que vou apagar");
	}

	@Test
	void repostDePostApagadoSaiDoDiario() throws Exception {
		var dono = auth.authenticate("diario_dono_repost");
		var terceiro = auth.authenticate("diario_terceiro_repost");
		String donoUsername = usernameOf(dono);

		String postDoTerceiro = publish(terceiro, "original que sera apagado", "diario-rdel-1");
		repost(dono, postDoTerceiro);

		MvcResult antes = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(antes.getResponse().getContentAsString()).contains("\"REPOST\"");

		// O autor original apaga o post: o repost perde o pra onde levar e sai da linha do dono.
		mockMvc.perform(delete("/feed/posts/" + postDoTerceiro)
				.header("X-Session-Token", terceiro.token())
				.header("Idempotency-Key", "diario-rdel-1")
				.queryParam("reason", "limpando"))
			.andExpect(status().isOk());

		MvcResult depois = mockMvc.perform(get("/users/" + donoUsername + "/diario")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(depois.getResponse().getContentAsString()).doesNotContain("\"REPOST\"");
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		var result = mockMvc.perform(get("/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString())
			.get("username").asText();
	}

	private String publish(TestAuthHelper.AuthSession session, String content, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key + "-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					java.util.Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content,
						"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "diario-c-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void repost(TestAuthHelper.AuthSession session, String postId) throws Exception {
		mockMvc.perform(post("/feed/posts/" + postId + "/repost")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isCreated());
	}
}
