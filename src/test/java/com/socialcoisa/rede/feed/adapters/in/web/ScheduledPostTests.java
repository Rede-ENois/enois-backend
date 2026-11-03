package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.application.ScheduledPostService;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.ScheduledPostRepository;
import com.socialcoisa.rede.feed.domain.ScheduledPost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Agendamento de publicação: criar/listar/cancelar pela API e a publicação em si, que só acontece
 * quando o relógio vence — pelo MESMO caso de uso do composer. Nada do agendado aparece antes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduledPostTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ScheduledPostRepository scheduledPosts;

	@Autowired
	private ScheduledPostService service;

	@Autowired
	private PostRepository posts;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String schedule(TestAuthHelper.AuthSession session, String key, String content, Instant quando,
			String communityId) throws Exception {
		Map<String, Object> body = communityId == null
			? Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content, "scheduledAt", quando.toString())
			: Map.of("type", "INSIGHT", "content", content, "scheduledAt", quando.toString(), "communityId",
				communityId);
		MvcResult result = mockMvc.perform(post("/feed/scheduled")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.scheduledAt").value(quando.toString()))
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** Simula o relógio passando: a API exige futuro, então o vencimento é forçado no banco. */
	private void backdate(String id) {
		ScheduledPost agendado = scheduledPosts.findById(id).orElseThrow();
		scheduledPosts.save(new ScheduledPost(agendado.id(), agendado.userId(), agendado.type(), agendado.content(),
			agendado.pageHtml(), agendado.articleBody(), agendado.tags(), agendado.mediaIds(),
			agendado.mentionedUserIds(), agendado.communityId(), agendado.topic(),
			Instant.now().minusSeconds(5), agendado.createdAt(), null, 0));
	}

	@Test
	void scheduleListAndCancel() throws Exception {
		var owner = auth.authenticate("user_sched_owner");
		String id = schedule(owner, "sched-owner-1", "Post agendado de teste.",
			Instant.now().plusSeconds(600), null);

		mockMvc.perform(get("/feed/scheduled").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].content").value("Post agendado de teste."));

		mockMvc.perform(delete("/feed/scheduled/" + id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/scheduled").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void pastDateIsRejected() throws Exception {
		var owner = auth.authenticate("user_sched_past");

		mockMvc.perform(post("/feed/scheduled")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sched-past-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "texto",
					"scheduledAt", Instant.now().minusSeconds(60).toString()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DATA_NO_PASSADO"));
	}

	@Test
	void standalonePostWithoutTopicIsRejected() throws Exception {
		var owner = auth.authenticate("user_sched_no_topic");

		mockMvc.perform(post("/feed/scheduled")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "sched-no-topic-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "INSIGHT", "content", "texto",
					"scheduledAt", Instant.now().plusSeconds(600).toString()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOPIC_REQUIRED"));
	}

	@Test
	void duePostIsPublishedByTheNormalUseCaseAndLeavesTheQueue() throws Exception {
		var owner = auth.authenticate("user_sched_due");
		String id = schedule(owner, "sched-due-1", "Post agendado devido.", Instant.now().plusSeconds(600), null);
		backdate(id);

		service.publishDue();

		// Saiu da fila...
		assertThat(scheduledPosts.findById(id)).isEmpty();
		// ...e virou post de verdade do autor, publicado pelo mesmo caminho do composer.
		var doAutor = posts.findActiveByAuthor(new UserId(owner.userId()), Instant.now().plusSeconds(60), 20);
		assertThat(doAutor).anyMatch(post -> "Post agendado devido.".equals(post.content()));
	}

	@Test
	void refusedPublishKeepsTheRowWithTheReason() throws Exception {
		var owner = auth.authenticate("user_sched_refused");
		String id = schedule(owner, "sched-refused-1", "Post para comunidade inexistente.",
			Instant.now().plusSeconds(600), "community_nao_existe");
		backdate(id);

		service.publishDue();

		// A recusa não some em silêncio: o item fica com o motivo visível para o autor.
		ScheduledPost depois = scheduledPosts.findById(id).orElseThrow();
		assertThat(depois.attempts()).isEqualTo(1);
		assertThat(depois.lastError()).contains("Comunidade");
	}
}
