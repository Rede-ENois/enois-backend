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
 * O Momento: nasce com prazo de 24h (expiresAt na resposta), some sozinho quando o worker roda
 * e os limites novos valem (Divertido 400, Momento 500).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MomentExpiryTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private MomentExpiryScheduler expiryScheduler;

	@Test
	void momentGets24hDeadlineAndWorkerDeletesIt() throws Exception {
		var author = auth("mom_author");

		// Momento publicado: a resposta carrega expiresAt ~24h à frente.
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mom-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "MOMENT", "content", "Tomando café na varanda ☕", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isOk()).andReturn();
		Instant expiresAt = Instant.parse(
			objectMapper.readTree(read.getResponse().getContentAsString()).get("expiresAt").asText());
		org.assertj.core.api.Assertions.assertThat(Duration.between(Instant.now(), expiresAt).toHours())
			.as("prazo de 24h").isBetween(23L, 24L);

		// Divertido: 401 recusa (limite 400); Momento: 501 recusa (limite 500).
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mom-div-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "DIVERTIDO",
					"content", "0123456789".repeat(41), "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mom-mom-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "MOMENT",
					"content", "0123456789".repeat(51), "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		// Um momento com prazo VENCIDO (inserido direto): o worker o apaga no fluxo normal.
		var authorId = new UserId(author.userId());
		var vencido = Post.restore(new PostId("post_mom_vencido"), authorId, PostType.MOMENT,
			"Momento que já passou do prazo", null, List.of(), List.of(), null,
			Instant.now().minus(Duration.ofHours(25)), Visibility.PUBLIC, Counters.zero(), PostStatus.ACTIVE,
			null, null, Instant.now().minus(Duration.ofHours(1)));
		postRepository.save(vencido);

		expiryScheduler.expireDueMoments();

		var depois = postRepository.findById(new PostId("post_mom_vencido")).orElseThrow();
		org.assertj.core.api.Assertions.assertThat(depois.isActive())
			.as("o momento vencido foi apagado pelo worker").isFalse();
		// E o post recente (dentro do prazo) continua ativo.
		var recente = postRepository.findById(new PostId(postId)).orElseThrow();
		org.assertj.core.api.Assertions.assertThat(recente.isActive()).isTrue();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
