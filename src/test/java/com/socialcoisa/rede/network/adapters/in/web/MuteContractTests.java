package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Silenciamento com prazo: enquanto vale, a pessoa some do feed de quem silenciou; quando vence,
 * volta a aparecer sozinho — sem ninguem desfazer. O silenciamento sem prazo continua valendo
 * para sempre, como antes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MuteContractTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void mutedAuthorLeavesTheFeedAndComesBackAfterTheDeadline() throws Exception {
		var author = auth.authenticate("mute_deadline_author");
		var reader = auth.authenticate("mute_deadline_reader");
		String postId = publishPost(author, "Conteudo de quem sera silenciado por um dia");

		assertThat(feedContainsPost(reader, postId)).isTrue();

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-24h-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "24h"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("MUTED"));

		assertThat(feedContainsPost(reader, postId)).isFalse();
		// A lista mostra o prazo, para a tela poder dizer quando volta.
		mockMvc.perform(get("/network/mutes").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId").value(author.userId()))
			.andExpect(jsonPath("$[0].expiresAt").isNotEmpty());

		// Aproxima o prazo do passado: o silenciamento vence sem ninguem tocar nele.
		jdbc.update("update user_mutes set expires_at = ? where muter_id = ? and muted_id = ?",
			Timestamp.from(Instant.now().minusSeconds(60)), reader.userId(), author.userId());

		assertThat(feedContainsPost(reader, postId)).isTrue();
		assertThat(mutesBody(reader)).doesNotContain(author.userId());
	}

	@Test
	void muteWithoutDurationStillHidesForever() throws Exception {
		var author = auth.authenticate("mute_forever_author");
		var reader = auth.authenticate("mute_forever_reader");
		String postId = publishPost(author, "Conteudo de quem sera silenciado sem prazo");

		// Sem corpo — o contrato antigo, que o front ja usa hoje.
		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-forever-1"))
			.andExpect(status().isCreated());

		assertThat(feedContainsPost(reader, postId)).isFalse();
		mockMvc.perform(get("/network/mutes").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId").value(author.userId()))
			.andExpect(jsonPath("$[0].expiresAt").doesNotExist());
	}

	@Test
	void mutingAgainRenewsTheDeadlineInsteadOfBeingIgnored() throws Exception {
		var author = auth.authenticate("mute_renew_author");
		var reader = auth.authenticate("mute_renew_reader");
		publishPost(author, "Conteudo de quem sera silenciado duas vezes");

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-renew-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "24h"))))
			.andExpect(status().isCreated());
		// O primeiro pedido ja venceu.
		jdbc.update("update user_mutes set expires_at = ? where muter_id = ? and muted_id = ?",
			Timestamp.from(Instant.now().minusSeconds(60)), reader.userId(), author.userId());

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-renew-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "7d"))))
			.andExpect(status().isCreated());

		Timestamp expiresAt = jdbc.queryForObject(
			"select expires_at from user_mutes where muter_id = ? and muted_id = ?", Timestamp.class,
			reader.userId(), author.userId());
		assertThat(expiresAt.toInstant()).isAfter(Instant.now().plusSeconds(6 * 24 * 3600));
		assertThat(jdbc.queryForObject("select count(*) from user_mutes where muter_id = ? and muted_id = ?",
			Integer.class, reader.userId(), author.userId())).isEqualTo(1);
	}

	@Test
	void unmuteHidesNothingAndRemovesTheRow() throws Exception {
		var author = auth.authenticate("mute_undo_author");
		var reader = auth.authenticate("mute_undo_reader");
		String postId = publishPost(author, "Conteudo de quem sera silenciado e liberado");

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-undo-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "72h"))))
			.andExpect(status().isCreated());
		assertThat(feedContainsPost(reader, postId)).isFalse();

		mockMvc.perform(delete("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-undo-2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UNMUTED"));

		assertThat(feedContainsPost(reader, postId)).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from user_mutes where muter_id = ? and muted_id = ?",
			Integer.class, reader.userId(), author.userId())).isZero();
	}

	@Test
	void rejectsAnUnknownOrOversizedDuration() throws Exception {
		var author = auth.authenticate("mute_invalid_author");
		var reader = auth.authenticate("mute_invalid_reader");

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-invalid-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "para sempre"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MUTE_DURATION_INVALID"));

		mockMvc.perform(post("/network/mutes/" + author.userId())
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "mute-invalid-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("duration", "400d"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MUTE_DURATION_TOO_LONG"));

		// Nada foi gravado: um texto invalido nao pode virar silenciamento eterno por engano.
		assertThat(jdbc.queryForObject("select count(*) from user_mutes where muter_id = ? and muted_id = ?",
			Integer.class, reader.userId(), author.userId())).isZero();
	}

	@Test
	void cannotMuteYourself() throws Exception {
		var session = auth.authenticate("mute_self");

		mockMvc.perform(post("/network/mutes/" + session.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "mute-self-1"))
			.andExpect(status().isBadRequest());
	}

	private String publishPost(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "mute-post-" + session.userId() + "-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", content,
					"tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** A aba ALL ve os posts de todo mundo, entao serve para observar um autor especifico. */
	private boolean feedContainsPost(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult result = mockMvc.perform(get("/feed")
				.param("tab", "ALL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return result.getResponse().getContentAsString().contains(postId);
	}

	private String mutesBody(TestAuthHelper.AuthSession session) throws Exception {
		return mockMvc.perform(get("/network/mutes").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}
}