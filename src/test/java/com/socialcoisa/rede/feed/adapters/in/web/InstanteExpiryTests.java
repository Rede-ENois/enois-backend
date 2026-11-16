package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.application.MomentExpiryScheduler;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.domain.Visibility;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O Instante: nasce com prazo de 1h (expiresAt na resposta), entra nos feeds comuns enquanto
 * vigente, some da listagem e do link direto quando vence (antes mesmo do worker passar) e o
 * worker o apaga no fluxo normal. Não é republicável.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstanteExpiryTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private MomentExpiryScheduler expiryScheduler;

	@Test
	void instantGets1hDeadlineFeedsAndExpiry() throws Exception {
		var author = auth("inst_author");

		// Instante publicado: a resposta carrega expiresAt ~1h à frente (e nunca 24h).
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inst-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSTANTE", "content", "Pôr do sol agora 🌇", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isOk()).andReturn();
		Instant expiresAt = Instant.parse(
			objectMapper.readTree(read.getResponse().getContentAsString()).get("expiresAt").asText());
		org.assertj.core.api.Assertions.assertThat(Duration.between(Instant.now(), expiresAt).toMinutes())
			.as("prazo de 1h").isBetween(55L, 60L);

		// Instante: texto em 500 — 501 recusa.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inst-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSTANTE",
					"content", "0123456789".repeat(51), "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		// Enquanto vigente, o Instante entra nos feeds comuns (Tudo) como qualquer post.
		MvcResult feed = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feed.getResponse().getContentAsString())
			.contains(postId);

		// Efêmero não é republicável (mesma regra do Momento).
		mockMvc.perform(post("/feed/posts/" + postId + "/repost")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inst-repost")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of())))
			.andExpect(status().isBadRequest());

		// Instante com prazo VENCIDO (inserido direto): some do link direto e do feed antes do
		// worker passar, e o worker o apaga no fluxo normal.
		var authorId = new UserId(author.userId());
		var vencido = Post.restore(new PostId("post_inst_vencido"), authorId, PostType.INSTANTE,
			"Instante que já passou do prazo", null, List.of(), List.of(), null,
			Instant.now().minus(Duration.ofHours(2)), Visibility.PUBLIC, Counters.zero(), PostStatus.ACTIVE,
			null, null, Instant.now().minus(Duration.ofHours(1)));
		postRepository.save(vencido);

		mockMvc.perform(get("/feed/posts/post_inst_vencido"))
			.andExpect(status().isNotFound());
		MvcResult feedDepois = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feedDepois.getResponse().getContentAsString())
			.doesNotContain("post_inst_vencido");

		expiryScheduler.expireDueMoments();

		var depois = postRepository.findById(new PostId("post_inst_vencido")).orElseThrow();
		org.assertj.core.api.Assertions.assertThat(depois.isActive())
			.as("o instante vencido foi apagado pelo worker").isFalse();
		// E o instante recente (dentro do prazo) continua ativo.
		var recente = postRepository.findById(new PostId(postId)).orElseThrow();
		org.assertj.core.api.Assertions.assertThat(recente.isActive()).isTrue();
	}

	@Test
	void switchingTypesMovesTheDeadlineLikeMoment() throws Exception {
		var author = auth("inst_author2");

		// Insight sem prazo vira Instante na edição: ganha 1h a partir da edição.
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inst-edit-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Texto fixo", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isOk()).andReturn();
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(read.getResponse().getContentAsString()).get("expiresAt").isNull())
			.as("insight nasce sem prazo").isTrue();

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inst-edit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "INSTANTE", "content", "Texto fixo"))))
			.andExpect(status().isOk());
		MvcResult depois = mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isOk()).andReturn();
		Instant expiresAt = Instant.parse(
			objectMapper.readTree(depois.getResponse().getContentAsString()).get("expiresAt").asText());
		org.assertj.core.api.Assertions.assertThat(Duration.between(Instant.now(), expiresAt).toMinutes())
			.as("virar Instante na edição dá 1h").isBetween(50L, 60L);
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
