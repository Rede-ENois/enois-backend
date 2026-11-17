package com.socialcoisa.rede.desabafos.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.moderation.application.ReportedContentRemover;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Os Desabafos de ponta a ponta: texto puro, feed próprio no Explorar, acolhida em comentário,
 * apoio contado sem lista de quem apoiou. O centro dos testes é o ISOLAMENTO — um desabafo não
 * é uma linha em `posts`, então por construção nada dele vaza para /feed, perfil, diário ou
 * busca; os testes provam isso nas respostas reais mesmo assim. Sem gamificação: sem ranking,
 * sem pontos — o feed é cronológico.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DesabafosFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportedContentRemover reportedContentRemover;

	@Test
	void publishesTextAndStaysInItsOwnSpace() throws Exception {
		var author = auth("des_author");

		// Publicação: texto simples vira desabafo com autor resolvido.
		MvcResult created = mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-pub-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content",
					"Estou cansado de fingir que está tudo bem hoje"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode post = objectMapper.readTree(created.getResponse().getContentAsString());
		String postId = post.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(postId).startsWith("desabafo_");
		org.assertj.core.api.Assertions.assertThat(post.get("author").get("id").asText()).isEqualTo(author.userId());
		org.assertj.core.api.Assertions.assertThat(post.get("content").asText()).contains("cansado");

		// O espaço próprio: aparece no feed dos Desabafos, em ordem cronológica.
		MvcResult propio = mockMvc.perform(get("/desabafos").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(propio.getResponse().getContentAsString()).contains(postId);

		// ISOLAMENTO — o desabafo não é um post: não vaza para o feed geral…
		MvcResult feedGeral = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feedGeral.getResponse().getContentAsString())
			.as("desabafo não vaza para o feed Tudo").doesNotContain(postId);

		// …nem para o perfil (posts)…
		String username = usernameOf(author);
		MvcResult perfil = mockMvc.perform(get("/users/" + username + "/posts")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(perfil.getResponse().getContentAsString())
			.as("desabafo não vaza para o perfil").doesNotContain(postId);

		// …nem para o diário de atividade…
		MvcResult diario = mockMvc.perform(get("/users/" + username + "/diario")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(diario.getResponse().getContentAsString())
			.as("desabafo não vaza para o diário").doesNotContain(postId);

		// …nem para o buscador (é texto indexável, e ainda assim não entra).
		MvcResult busca = mockMvc.perform(get("/search").param("q", "cansado de fingir")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(busca.getResponse().getContentAsString())
			.as("desabafo não vaza para a busca").doesNotContain(postId);

		// Regras de publicação: vazio e comprido demais recusam; sem chave de idempotência nem começa.
		mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-empty-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_REQUIRED"));
		mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-long-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "x".repeat(3001)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
		mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "sem chave"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		// Mesma chave = mesmo resultado: replay devolve o MESMO desabafo, sem duplicar.
		String chave = "des-replay-" + UUID.randomUUID();
		MvcResult replay = mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "replay do desabafo"))))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult replay2 = mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "replay do desabafo"))))
			.andExpect(status().isCreated())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(replay2.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText());
	}

	@Test
	void desabafosExigemSessao() throws Exception {
		// Espaço de convivência, não página pública: sem sessão não há leitura nem escrita.
		mockMvc.perform(get("/desabafos")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/desabafos/posts/qualquer")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/desabafos/posts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"sem sessao\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void commentsWelcomeNotifyAndStayContained() throws Exception {
		var author = auth("des_cauthor");
		var visitor = auth("des_cvisitor");
		String postId = publish(author, "Hoje foi um dia difícil para contar");

		// Acolhida em texto puro.
		MvcResult acolhida = mockMvc.perform(post("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-c1-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content",
					"Estou aqui com você, obrigada por contar"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode comentario = objectMapper.readTree(acolhida.getResponse().getContentAsString());
		String commentId = comentario.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(comentario.get("author").get("id").asText())
			.isEqualTo(visitor.userId());

		// Vazio e comprido demais recusam.
		mockMvc.perform(post("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-c2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", " "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_EMPTY"));
		mockMvc.perform(post("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-c3-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "y".repeat(801)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_TOO_LONG"));
		// Desabafo inexistente não recebe acolhida.
		mockMvc.perform(post("/desabafos/posts/desabafo_nada/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-c4-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "oi"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		// A listagem traz a acolhida e a contagem chega ao post.
		MvcResult lista = mockMvc.perform(get("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(lista.getResponse().getContentAsString()).get("items").size()).isEqualTo(1);
		MvcResult postDepois = mockMvc.perform(get("/desabafos/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(postDepois.getResponse().getContentAsString()).get("comments").asLong())
			.isEqualTo(1);

		// O autor do desabafo recebe a notificação de acolhida (kind mapeado em SOCIAL).
		MvcResult notificacoes = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(notificacoes.getResponse().getContentAsString())
			.contains("DESABAFOS_COMMENTED");

		// O autor do desabafo NÃO remove a acolhida alheia; o visitante remove a própria.
		mockMvc.perform(delete("/desabafos/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-cd-outro-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(delete("/desabafos/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-cd-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult listaDepois = mockMvc.perform(get("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(listaDepois.getResponse().getContentAsString()).get("items").size()).isZero();
	}

	@Test
	void reactionsCountWithoutNamingWhoSupported() throws Exception {
		var author = auth("des_rauthor");
		var leitor = auth("des_rleitor");
		var neutro = auth("des_rneutro");
		String postId = publish(author, "Preciso de apoio, não de palpite");

		// Apoio entra e soma.
		mockMvc.perform(post("/desabafos/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "des-r1-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("reaction", "SUPPORT"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.SUPPORT").value(1));

		// Trocar o tipo não cria segunda reação: SUPPORT sai, LOVE entra.
		mockMvc.perform(post("/desabafos/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "des-r2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("reaction", "LOVE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.LOVE").value(1))
			.andExpect(jsonPath("$.SUPPORT").doesNotExist());

		// Reação desconhecida recusa com 400 legível.
		mockMvc.perform(post("/desabafos/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "des-r3-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("reaction", "NAO_EXISTO"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REACTION"));

		// O post mostra o total e o MEU tipo — e nada além disso.
		MvcResult post = mockMvc.perform(get("/desabafos/posts/" + postId)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode view = objectMapper.readTree(post.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(view.get("reactions").asLong()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(view.get("myReaction").asText()).isEqualTo("LOVE");

		// SEM EXPOSIÇÃO: para um terceiro, o feed e o post não contêm o id de quem apoiou —
		// reação é contada, nunca nominada (e o "myReaction" dele é nulo).
		MvcResult olhoNeutro = mockMvc.perform(get("/desabafos")
				.header("X-Session-Token", neutro.token()))
			.andExpect(status().isOk())
			.andReturn();
		String feedNeutro = olhoNeutro.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(feedNeutro).contains(postId)
			.as("quem apoiou não aparece em lugar nenhum").doesNotContain(leitor.userId());
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(feedNeutro).get("items").get(0).get("myReaction").isNull()).isTrue();

		// A reação sai quando a pessoa remove.
		mockMvc.perform(delete("/desabafos/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "des-r4-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult postLimpo = mockMvc.perform(get("/desabafos/posts/" + postId)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(postLimpo.getResponse().getContentAsString()).get("reactions").asLong())
			.isZero();
	}

	@Test
	void blockedAuthorVanishesAndPaginationWalksPages() throws Exception {
		var leitor = auth("des_pleitor");
		var bloqueado = auth("des_pbloqueado");
		String ida = publish(bloqueado, "desabafo de quem foi bloqueado");
		String volta = publish(leitor, "meu próprio desabafo");

		// Página curta: 1 por página e cursor para a próxima (sem ranking: ordem cronológica).
		MvcResult primeira = mockMvc.perform(get("/desabafos").param("limit", "1")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode pagina = objectMapper.readTree(primeira.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(pagina.get("items").size()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(pagina.get("nextCursor").isTextual()).isTrue();
		String cursor = pagina.get("nextCursor").asText();

		MvcResult segunda = mockMvc.perform(get("/desabafos").param("limit", "1").param("cursor", cursor)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode paginaSeguinte = objectMapper.readTree(segunda.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(paginaSeguinte.get("items").size()).isEqualTo(1);
		String primeiraId = pagina.get("items").get(0).get("id").asText();
		String segundaId = paginaSeguinte.get("items").get(0).get("id").asText();
		org.assertj.core.api.Assertions.assertThat(primeiraId).isNotEqualTo(segundaId);

		// Bloqueio: os desabafos de quem foi bloqueado somem do feed de quem bloqueou…
		mockMvc.perform(post("/network/blocks/" + bloqueado.userId())
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "des-block-" + UUID.randomUUID()))
			.andExpect(status().isCreated());
		MvcResult depoisBloqueio = mockMvc.perform(get("/desabafos")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(depoisBloqueio.getResponse().getContentAsString())
			.doesNotContain(ida).contains(volta);

		// …mas continuam visíveis para quem não tem bloqueio nenhum.
		var alheio = auth("des_palheio");
		MvcResult olhoNeutro = mockMvc.perform(get("/desabafos")
				.header("X-Session-Token", alheio.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(olhoNeutro.getResponse().getContentAsString()).contains(ida);
	}

	@Test
	void reportConfirmedRemovesDesabafoAndItsAcolhidas() throws Exception {
		var author = auth("des_mauthor");
		var visitor = auth("des_mvisitor");
		String postId = publish(author, "desabafo que vai ser denunciado");
		String commentId = comment(visitor, postId, "acolhida que cai junto");

		// Denúncia confirmada no DESABAFO: ReportedContentRemover derruba o post pelo alvo
		// DESABAFO_POST — e as acolhidas caem com ele (ficam inativas junto do post).
		Report report = Report.create("report_des_" + UUID.randomUUID().toString().replace("-", ""),
			visitor.userId(), TargetType.DESABAFO_POST, postId, ReportReason.SPAM, "spam", Instant.now());
		reportedContentRemover.remove(report, "moderador_1", "TESTE");
		mockMvc.perform(get("/desabafos/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
		mockMvc.perform(get("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isNotFound());
		MvcResult feed = mockMvc.perform(get("/desabafos").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feed.getResponse().getContentAsString()).doesNotContain(postId);

		// Denúncia confirmada na ACOLHIDA: pelo alvo DESABAFO_COMMENT, o comentário sai e o
		// desabafo continua no ar.
		String postId2 = publish(author, "desabafo que permanece");
		String commentId2 = comment(visitor, postId2, "acolhida denunciada sozinha");
		Report report2 = Report.create("report_des2_" + UUID.randomUUID().toString().replace("-", ""),
			author.userId(), TargetType.DESABAFO_COMMENT, commentId2, ReportReason.SPAM, "spam", Instant.now());
		reportedContentRemover.remove(report2, "moderador_1", "TESTE");
		MvcResult lista = mockMvc.perform(get("/desabafos/posts/" + postId2 + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString())
			.doesNotContain(commentId).doesNotContain(commentId2);
		mockMvc.perform(get("/desabafos/posts/" + postId2)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
		org.assertj.core.api.Assertions.assertThat(commentId).isNotEqualTo(commentId2);
	}

	@Test
	void onlyTheAuthorRemovesTheOwnDesabafo() throws Exception {
		var author = auth("des_dauthor");
		var visitor = auth("des_dvisitor");
		String postId = publish(author, "desabafo que eu mesma vou remover");

		mockMvc.perform(delete("/desabafos/posts/" + postId)
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "des-del-outro-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));

		mockMvc.perform(delete("/desabafos/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-del-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/desabafos/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isNotFound());
	}

	private String publish(TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult created = mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "des-flow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content) throws Exception {
		MvcResult created = mockMvc.perform(post("/desabafos/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "des-flow-c-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		var result = mockMvc.perform(get("/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString())
			.get("username").asText();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
