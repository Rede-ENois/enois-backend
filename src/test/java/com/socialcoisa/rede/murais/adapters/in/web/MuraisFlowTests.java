package com.socialcoisa.rede.murais.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialcoisa.rede.moderation.application.ReportedContentRemover;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Os Murais de ponta a ponta: coleção colaborativa de publicações que JÁ existem no feed. O elo
 * não cria conteúdo — quem cola aponta post alheio, que continua com seus comentários, reações e
 * moderação próprios. Cobrirem-se aqui: criação/edição só do dono, contribuição com visibilidade
 * do feed, sem duplicar elo, notificação ao autor, remoção por quem colou ou pelo dono, post
 * removido saindo do mural sem quebrar a página, denúncia confirmada derrubando o QUADRO (e
 * nunca os posts) e idempotência.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MuraisFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportedContentRemover reportedContentRemover;

	@Test
	void createsValidatesAndListsMuralsWithIdempotency() throws Exception {
		var owner = auth("mural_owner");

		// Validação: sem título, título longo e descrição longa recusam.
		mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-vazio-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("description", "sem título"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MURAL_TITLE_REQUIRED"));
		mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-longo-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "m".repeat(121)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MURAL_TITLE_TOO_LONG"));
		mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-desc-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "híbrido", "description", "d".repeat(501)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		// Sem Idempotency-Key a escrita nem começa.
		mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "sem chave"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		// Criação: id próprio, dono resolvido e contagem zerada.
		MvcResult criado = mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-cria-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Fotografia do interior",
					"description", "Retratos e paisagens de quem vive longe do centro"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode mural = objectMapper.readTree(criado.getResponse().getContentAsString());
		String muralId = mural.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(muralId).startsWith("mural_");
		org.assertj.core.api.Assertions.assertThat(mural.get("owner").get("id").asText()).isEqualTo(owner.userId());
		org.assertj.core.api.Assertions.assertThat(mural.get("contributions").asLong()).isZero();

		// Mesma chave = mesmo resultado: replay devolve o MESMO mural, sem duplicar.
		String chave = "mural-replay-" + UUID.randomUUID();
		MvcResult replay = mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult replay2 = mockMvc.perform(post("/murais").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(replay2.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText());

		// Listagem e detalhe mostram o mural criado.
		MvcResult lista = mockMvc.perform(get("/murais").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString()).contains(muralId);
		MvcResult detalhe = mockMvc.perform(get("/murais/" + muralId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(detalhe.getResponse().getContentAsString()).get("title").asText())
			.isEqualTo("Fotografia do interior");
	}

	@Test
	void contributesFeedPostsAndNotifiesTheAuthor() throws Exception {
		var author = auth("mural_postauthor");
		var visitor = auth("mural_visitor");
		var owner = auth("mural_mowner");
		String muralId = createMural(owner, "Mural do autor");
		String postId = publishPost(author, "Um lugar que eu queria mostrar para todo mundo");

		// Visitante cola post ALHEIO: 201, a publicação vem inteira (é a do feed, enriquecida).
		MvcResult colado = mockMvc.perform(post("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "mural-col-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode contribuicao = objectMapper.readTree(colado.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(contribuicao.get("postId").asText()).isEqualTo(postId);
		org.assertj.core.api.Assertions.assertThat(contribuicao.get("post").get("content").asText())
			.contains("Um lugar que eu queria mostrar");
		org.assertj.core.api.Assertions.assertThat(contribuicao.get("addedBy").get("id").asText())
			.isEqualTo(visitor.userId());

		// O mesmo post no mesmo mural não cola de novo.
		mockMvc.perform(post("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "mural-col2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JA_NO_MURAL"));

		// Post inexistente recusa: mural não é vitrine de nada que não dá para ver.
		mockMvc.perform(post("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "mural-fantasma-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", "post_inexistente"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		// A página de contribuições traz o post com quem colou; a contagem do mural sobe.
		MvcResult pagina = mockMvc.perform(get("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString()).contains(postId);
		MvcResult detalhe = mockMvc.perform(get("/murais/" + muralId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(detalhe.getResponse().getContentAsString()).get("contributions")
				.asLong())
			.isEqualTo(1);

		// O autor do post recebe a notificação de contribuição (kind mapeado em SOCIAL).
		MvcResult notificacoes = mockMvc.perform(get("/notifications").param("context", "SOCIAL")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(notificacoes.getResponse().getContentAsString())
			.contains("MURAL_CONTRIBUTED");
	}

	@Test
	void onlyOwnerEditsAndRemovesMuralAndPinsRemoveTheirOwn() throws Exception {
		var owner = auth("mural_edowner");
		var visitor = auth("mural_edvisitor");
		var autor = auth("mural_edauthor");
		String muralId = createMural(owner, "Vai ser editado");
		String postId = publishPost(autor, "post de mural para edição");

		// Edição e remoção do quadro: só o dono.
		mockMvc.perform(patch("/murais/" + muralId).header("X-Session-Token", visitor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "título roubado"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(patch("/murais/" + muralId).header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Fotos da feira", "description",
					"O que a feira tem de bonito"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Fotos da feira"));

		// Cola o post: quem colou tira; terceiro não tira.
		contribute(visitor, muralId, postId);
		mockMvc.perform(delete("/murais/" + muralId + "/contribuicoes/" + postId)
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "mural-out-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(delete("/murais/" + muralId + "/contribuicoes/" + postId)
				.header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "mural-del-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult pagina = mockMvc.perform(get("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString()).doesNotContain(postId);

		// Depois de tirado, cola de novo; desta vez o DONO do mural tira.
		contribute(visitor, muralId, postId);
		mockMvc.perform(delete("/murais/" + muralId + "/contribuicoes/" + postId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-del2-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		// Remoção do quadro: só o dono; depois disso, 404 para todos.
		mockMvc.perform(delete("/murais/" + muralId).header("X-Session-Token", visitor.token())
				.header("Idempotency-Key", "mural-rm-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(delete("/murais/" + muralId).header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-rm-ok-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/murais/" + muralId).header("X-Session-Token", owner.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("MURAL_NOT_FOUND"));
	}

	@Test
	void removedPostDropsOutOfMuralAndConfirmedReportTakesOnlyTheMuralDown() throws Exception {
		var author = auth("mural_rauthor");
		var visitor = auth("mural_rvisitor");
		var owner = auth("mural_rownner");
		String muralId = createMural(owner, "Mural que vai cair");
		String postId = publishPost(author, "post que o autor vai apagar");
		String postId2 = publishPost(author, "post que fica no feed");
		contribute(visitor, muralId, postId);
		contribute(visitor, muralId, postId2);

		// Autor apaga o post do feed: o elo fica na base, mas o post SAI da página do mural —
		// e a página continua inteira com o post que ficou.
		mockMvc.perform(delete("/feed/posts/" + postId).header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mural-postdel-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult pagina = mockMvc.perform(get("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString())
			.doesNotContain(postId)
			.contains(postId2);

		// Denúncia confirmada do MURAL: o quadro cai, mas as publicações coladas continuam no feed.
		Report report = Report.create("report_mural_" + UUID.randomUUID().toString().replace("-", ""),
			visitor.userId(), TargetType.MURAL, muralId, ReportReason.SPAM, "spam", Instant.now());
		reportedContentRemover.remove(report, "moderador_1", "TESTE");
		mockMvc.perform(get("/murais/" + muralId).header("X-Session-Token", owner.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("MURAL_NOT_FOUND"));
		MvcResult feed = mockMvc.perform(get("/feed/posts/" + postId2).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feed.getResponse().getContentAsString()).contains(postId2);
	}

	@Test
	void seletorDoPostListaSoOsMeusMuraisComMarcaDeOndeOPostJaEsta() throws Exception {
		var dono = auth("mural_selowner");
		var outra = auth("mural_seloutra");
		var autor = auth("mural_selautor");
		String meuMural = createMural(dono, "Meus muros de referências");
		String muralAlheio = createMural(outra, "Mural que NÃO pode aparecer");
		String postId = publishPost(autor, "post que o seletor vai colar");

		// Antes da cola: o meu mural aparece com tem=false; o alheio não aparece.
		MvcResult antes = mockMvc.perform(get("/murais/meus").header("X-Session-Token", dono.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		String antesJson = antes.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(antesJson).contains(meuMural).doesNotContain(muralAlheio);
		JsonNode itemAntes = objectMapper.readTree(antesJson).get("items").get(0);
		org.assertj.core.api.Assertions.assertThat(itemAntes.get("tem").asBoolean()).isFalse();
		org.assertj.core.api.Assertions.assertThat(itemAntes.get("contributions").asLong()).isZero();

		// Colado: a flag vira true e a contagem sobe.
		contribute(dono, meuMural, postId);
		MvcResult depois = mockMvc.perform(get("/murais/meus").header("X-Session-Token", dono.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode itemDepois = objectMapper.readTree(depois.getResponse().getContentAsString()).get("items").get(0);
		org.assertj.core.api.Assertions.assertThat(itemDepois.get("tem").asBoolean()).isTrue();
		org.assertj.core.api.Assertions.assertThat(itemDepois.get("contributions").asLong()).isEqualTo(1);

		// Outra pessoa tem a lista PRÓPRIA: nem vê o meu quadro.
		MvcResult deOutra = mockMvc.perform(get("/murais/meus").header("X-Session-Token", outra.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(deOutra.getResponse().getContentAsString()).contains(muralAlheio)
			.doesNotContain(meuMural);

		// Sem sessão nem existe seletor: 401, como o resto do módulo.
		mockMvc.perform(get("/murais/meus").param("postId", postId))
			.andExpect(status().isUnauthorized());
	}

	// ---- Auxiliares ----

	private String createMural(TestAuthHelper.AuthSession owner, String title) throws Exception {
		MvcResult criado = mockMvc.perform(post("/murais")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "mural-flow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", title))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private String publishPost(TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult criado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mural-post-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", content, "tags", java.util.List.of(), "mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private void contribute(TestAuthHelper.AuthSession user, String muralId, String postId) throws Exception {
		mockMvc.perform(post("/murais/" + muralId + "/contribuicoes")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "mural-cflow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isCreated());
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
