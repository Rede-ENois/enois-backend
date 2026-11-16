package com.socialcoisa.rede.colagem.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

import com.socialcoisa.rede.colagem.application.ColagemService;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.moderation.application.ReportedContentRemover;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A Colagem de ponta a ponta: uma foto por publicação, feed próprio (fora de /feed), comentário
 * com no máximo uma foto e sem foto em resposta de foto, reação única por pessoa, bloqueio,
 * moderação (denúncia confirmada e takedown de mídia) e idempotência.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ColagemFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private ReportedContentRemover reportedContentRemover;

	@Autowired
	private ColagemService colagemService;

	@Test
	void publishesOnePhotoFeedsAndRejectsWrongInput() throws Exception {
		var author = auth("col_author");
		String foto = asset(author.userId(), "image/jpeg");
		String video = asset(author.userId(), "video/mp4");

		// Publicação: a resposta carrega a foto resolvida (url, mime) e o autor.
		MvcResult created = mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-pub-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", foto, "description",
					"Meu muro pintado de azul hoje"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode post = objectMapper.readTree(created.getResponse().getContentAsString());
		String postId = post.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(postId).startsWith("colagem_");
		org.assertj.core.api.Assertions.assertThat(post.get("mediaUrl").asText()).contains(foto);
		org.assertj.core.api.Assertions.assertThat(post.get("mimeType").asText()).isEqualTo("image/jpeg");

		// A Colagem é um espaço próprio: NADA dela aparece no feed geral (Tudo).
		MvcResult feedGeral = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feedGeral.getResponse().getContentAsString())
			.as("colagem não vaza para o feed Tudo").doesNotContain(postId);

		// E aparece no feed próprio, com o dono vendo a própria foto.
		MvcResult colagem = mockMvc.perform(get("/colagem").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode page = objectMapper.readTree(colagem.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(page.get("items").toString()).contains(postId);

		// Regras de publicação: sem foto, vídeo, legenda longa e mídia alheia recusam.
		mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-empty-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("description", "sem foto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_REQUIRED"));
		mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-video-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", video))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_IMAGE"));
		mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-long-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", foto, "description", "x".repeat(501)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		var outra = auth("col_outro");
		String alheia = asset(outra.userId(), "image/jpeg");
		mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-alheia-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", alheia))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEDIA_OWNER_MISMATCH"));

		// Sem Idempotency-Key a escrita nem começa.
		mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", foto))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		// Mesma chave = mesmo resultado: replay devolve a MESMA foto publicada, sem duplicar.
		String chave = "col-replay-" + UUID.randomUUID();
		MvcResult replay = mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", foto, "description", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult replay2 = mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", foto, "description", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(replay2.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText());
	}

	@Test
	void commentsRespectPhotoRulesAndNotifyTheAuthor() throws Exception {
		var author = auth("col_cauthor");
		var visitor = auth("col_cvisitor");
		String foto = asset(author.userId(), "image/jpeg");
		String postId = publish(author, foto);

		// Texto simples.
		MvcResult comentario = mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-c1-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Que azul é esse?"))))
			.andExpect(status().isCreated())
			.andReturn();
		String commentId = objectMapper.readTree(comentario.getResponse().getContentAsString()).get("id").asText();

		// Foto em comentário de primeiro nível: permitido (exatamente uma por comentário).
		String fotoVisitante = asset(visitor.userId(), "image/jpeg");
		MvcResult comFoto = mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-c2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", fotoVisitante))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode comentarioFoto = objectMapper.readTree(comFoto.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(comentarioFoto.get("mediaUrl").asText()).contains(fotoVisitante);
		String photoCommentId = comentarioFoto.get("id").asText();

		// Sem recursão: resposta a comentário fotográfico não carrega outra foto.
		mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-c3-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("parentCommentId", photoCommentId,
					"mediaId", foto))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PHOTO_REPLY_NOT_ALLOWED"));
		// Resposta em TEXTO ao comentário fotográfico passa.
		mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-c4-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("parentCommentId", photoCommentId,
					"content", "Fotografei do mesmo ângulo!"))))
			.andExpect(status().isCreated());

		// Vazio (sem texto e sem foto) e comprido demais recusam; pai de outro post não existe.
		mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-c5-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_EMPTY"));
		mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-c6-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "y".repeat(801)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_TOO_LONG"));
		mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-c7-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("parentCommentId", "colagem_c_inexistente",
					"content", "resposta órfã"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));

		// A listagem é crônica e traz contagem certa no post.
		MvcResult lista = mockMvc.perform(get("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode commentsPage = objectMapper.readTree(lista.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(commentsPage.get("items").size()).isEqualTo(3);
		MvcResult postDepois = mockMvc.perform(get("/colagem/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(postDepois.getResponse().getContentAsString()).get("comments").asLong())
			.isEqualTo(3);

		// O autor da foto recebe a notificação de contribuição (kind mapeado em SOCIAL).
		MvcResult notificacoes = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(notificacoes.getResponse().getContentAsString())
			.contains("COLLAGEM_COMMENTED");

		// O autor do comentário remove o próprio comentário; o visitante não remove o alheio.
		mockMvc.perform(delete("/colagem/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-cd-outro-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(delete("/colagem/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-cd-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult listaDepois = mockMvc.perform(get("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(listaDepois.getResponse().getContentAsString()).get("items").size())
			.isEqualTo(2);
	}

	@Test
	void oneReactionPerPersonSwappableAndRemovable() throws Exception {
		var author = auth("col_rauthor");
		var leitor = auth("col_rleitor");
		String postId = publish(author, asset(author.userId(), "image/jpeg"));

		// Reação válida entra e soma.
		mockMvc.perform(post("/colagem/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "col-r1-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reaction", "LIKE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.LIKE").value(1));

		// Trocar o tipo não cria segunda reação: LIKE sai, FIRE entra.
		mockMvc.perform(post("/colagem/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "col-r2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reaction", "FIRE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.FIRE").value(1))
			.andExpect(jsonPath("$.LIKE").doesNotExist());

		// Reação desconhecida recusa com 400 legível.
		mockMvc.perform(post("/colagem/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "col-r3-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reaction", "NAO_EXISTO"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REACTION"));

		// A reação aparece no post (total + o meu tipo)…
		MvcResult post = mockMvc.perform(get("/colagem/posts/" + postId)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode view = objectMapper.readTree(post.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(view.get("reactions").asLong()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(view.get("myReaction").asText()).isEqualTo("FIRE");

		// …e sai quando a pessoa remove.
		mockMvc.perform(delete("/colagem/posts/" + postId + "/reactions")
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "col-r4-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult postLimpo = mockMvc.perform(get("/colagem/posts/" + postId)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(postLimpo.getResponse().getContentAsString()).get("reactions").asLong())
			.isZero();
	}

	@Test
	void blockedAuthorVanishesFromTheFeedAndPaginationWalksPages() throws Exception {
		var leitor = auth("col_pleitor");
		var bloqueado = auth("col_pbloqueado");
		String ida = publish(bloqueado, asset(bloqueado.userId(), "image/jpeg"));
		String volta = publish(leitor, asset(leitor.userId(), "image/jpeg"));

		// Página curta: 1 por página e cursor para a próxima.
		MvcResult primeira = mockMvc.perform(get("/colagem").param("limit", "1")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode pagina = objectMapper.readTree(primeira.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(pagina.get("items").size()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(pagina.get("nextCursor").isTextual()).isTrue();
		String cursor = pagina.get("nextCursor").asText();

		MvcResult segunda = mockMvc.perform(get("/colagem").param("limit", "1").param("cursor", cursor)
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode paginaSeguinte = objectMapper.readTree(segunda.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(paginaSeguinte.get("items").size()).isEqualTo(1);
		String primeiraId = pagina.get("items").get(0).get("id").asText();
		String segundaId = paginaSeguinte.get("items").get(0).get("id").asText();
		org.assertj.core.api.Assertions.assertThat(primeiraId).isNotEqualTo(segundaId);

		// Bloqueio: as fotos de quem foi bloqueado somem do feed de quem bloqueou…
		mockMvc.perform(post("/network/blocks/" + bloqueado.userId())
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "col-block-" + UUID.randomUUID()))
			.andExpect(status().isCreated());
		MvcResult depoisBloqueio = mockMvc.perform(get("/colagem")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		String feed = depoisBloqueio.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(feed).doesNotContain(ida).contains(volta);

		// …mas continuam visíveis para quem não tem bloqueio nenhum.
		var alheio = auth("col_palheio");
		MvcResult olhoNeutro = mockMvc.perform(get("/colagem")
				.header("X-Session-Token", alheio.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(olhoNeutro.getResponse().getContentAsString()).contains(ida);
	}

	@Test
	void reportConfirmedAndMediaTakedownRemoveEverything() throws Exception {
		var author = auth("col_mauthor");
		var visitor = auth("col_mvisitor");
		String foto = asset(author.userId(), "image/jpeg");
		String postId = publish(author, foto);

		// Comentário fotográfico do VISITANTE (a mídia tem de ser dele): o takedown de ESTA mídia
		// derruba o comentário sem tocar na foto do autor.
		String fotoVisitante = asset(visitor.userId(), "image/jpeg");
		MvcResult comFoto = mockMvc.perform(post("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-mc-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", fotoVisitante))))
			.andExpect(status().isCreated())
			.andReturn();
		String photoCommentId = objectMapper.readTree(comFoto.getResponse().getContentAsString()).get("id").asText();
		org.assertj.core.api.Assertions.assertThat(colagemService.removeByMedia(fotoVisitante, "teste")).isEqualTo(1);
		MvcResult lista = mockMvc.perform(get("/colagem/posts/" + postId + "/comments")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString())
			.doesNotContain(photoCommentId);

		// Denúncia confirmada: ReportedContentRemover derruba a foto pelo novo alvo COLLAGEM_POST.
		Report report = Report.create("report_col_" + UUID.randomUUID().toString().replace("-", ""),
			visitor.userId(), TargetType.COLLAGEM_POST, postId, ReportReason.SPAM, "spam", Instant.now());
		reportedContentRemover.remove(report, "moderador_1", "TESTE");

		MvcResult aposDenuncia = mockMvc.perform(get("/colagem/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isNotFound())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(aposDenuncia.getResponse().getContentAsString()).get("code").asText())
			.isEqualTo("POST_NOT_FOUND");

		// Takedown de mídia na foto: publica de novo e derruba via ColagemModerationPort — a foto
		// sai do ar E some do feed, porque sem mídia visível não há Colagem.
		String outra = asset(author.userId(), "image/jpeg");
		String postId2 = publish(author, outra);
		int removidos = colagemService.removeByMedia(outra, "teste de takedown");
		org.assertj.core.api.Assertions.assertThat(removidos).isEqualTo(1);
		MvcResult feed = mockMvc.perform(get("/colagem")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feed.getResponse().getContentAsString()).doesNotContain(postId2);
	}

	@Test
	void onlyTheAuthorRemovesTheOwnPhoto() throws Exception {
		var author = auth("col_dauthor");
		var visitor = auth("col_dvisitor");
		String postId = publish(author, asset(author.userId(), "image/jpeg"));

		mockMvc.perform(delete("/colagem/posts/" + postId)
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "col-del-outro-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));

		mockMvc.perform(delete("/colagem/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-del-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/colagem/posts/" + postId)
				.header("X-Session-Token", author.token()))
			.andExpect(status().isNotFound());
	}

	private String publish(TestAuthHelper.AuthSession author, String mediaId) throws Exception {
		MvcResult created = mockMvc.perform(post("/colagem/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "col-flow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mediaId", mediaId))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String asset(String ownerId, String mime) {
		String id = "media_col" + UUID.randomUUID().toString().replace("-", "");
		Instant now = Instant.now();
		assets.save(MediaAsset.pending(id, new com.socialcoisa.rede.feed.domain.UserId(ownerId), "file", mime, 10,
			"POST_IMAGE", "uploads/" + id + "/file", "https://enois.exemplo.com/api/storage/uploads/" + id + "/file",
			now, now.plus(1, java.time.temporal.ChronoUnit.HOURS), MediaStorageProvider.S3));
		return id;
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
