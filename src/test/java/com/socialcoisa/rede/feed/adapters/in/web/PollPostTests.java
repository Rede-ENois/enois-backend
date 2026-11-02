package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Enquete (PostType.POLL): titulo ate 400, de 2 a 6 alternativas, voto unico por pessoa e SEM troca
 * depois. A imutabilidade e o ponto do recurso, entao ela tem teste proprio: o segundo voto (na
 * mesma alternativa ou em outra) responde 409 e a contagem nao muda.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PollPostTests {

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
	void publishesPollWithOptionsAndNoVotes() throws Exception {
		var author = auth.authenticate("user_poll_pub1");
		String postId = publishPoll(author, "Qual linguagem voce usa?",
			List.of("Java", "Kotlin", "Go"), "poll-pub-1");

		JsonNode poll = readPoll(author, postId);
		assertThat(poll.get("options")).hasSize(3);
		assertThat(poll.get("options").get(0).asText()).isEqualTo("Java");
		assertThat(poll.get("counts").get(0).asLong()).isZero();
		assertThat(poll.get("totalVotes").asLong()).isZero();
		assertThat(poll.get("myVote").isNull()).isTrue();
	}

	@Test
	void voteIsCountedOnceAndOnlyForTheChosenOption() throws Exception {
		var author = auth.authenticate("user_poll_vote1");
		var voter = auth.authenticate("user_poll_voter1");
		String postId = publishPoll(author, "Qual o melhor horario?", List.of("Manha", "Tarde"), "poll-vote-1");

		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", voter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.myVote").value(1))
			.andExpect(jsonPath("$.totalVotes").value(1))
			.andExpect(jsonPath("$.counts[0]").value(0))
			.andExpect(jsonPath("$.counts[1]").value(1));

		JsonNode poll = readPoll(voter, postId);
		assertThat(poll.get("myVote").asInt()).isEqualTo(1);
		assertThat(poll.get("totalVotes").asLong()).isEqualTo(1);
		assertThat(poll.get("counts").get(1).asLong()).isEqualTo(1);
	}

	@Test
	void cannotChangeVoteAfterCasting() throws Exception {
		var author = auth.authenticate("user_poll_change");
		var voter = auth.authenticate("user_poll_changer");
		String postId = publishPoll(author, "Cafe ou cha?", List.of("Cafe", "Cha"), "poll-change-1");

		vote(voter, postId, 0);

		// Trocar de alternativa e recusado: o voto e definitivo.
		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", voter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_VOTED"));

		// Votar de novo na MESMA alternativa tambem: nao ha "confirmar", o primeiro voto vale.
		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", voter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":0}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_VOTED"));

		JsonNode poll = readPoll(voter, postId);
		assertThat(poll.get("myVote").asInt()).isZero();
		assertThat(poll.get("totalVotes").asLong()).isEqualTo(1);
		assertThat(poll.get("counts").get(0).asLong()).isEqualTo(1);
		assertThat(poll.get("counts").get(1).asLong()).isZero();
	}

	@Test
	void eachPersonCountsOnceInTheTotal() throws Exception {
		var author = auth.authenticate("user_poll_people");
		var first = auth.authenticate("user_poll_people_a");
		var second = auth.authenticate("user_poll_people_b");
		String postId = publishPoll(author, "Escolha uma", List.of("A", "B"), "poll-people-1");

		vote(first, postId, 0);
		vote(second, postId, 0);

		JsonNode poll = readPoll(first, postId);
		assertThat(poll.get("totalVotes").asLong()).isEqualTo(2);
		assertThat(poll.get("counts").get(0).asLong()).isEqualTo(2);
	}

	@Test
	void rejectsInvalidOption() throws Exception {
		var author = auth.authenticate("user_poll_bad_opt");
		var voter = auth.authenticate("user_poll_bad_voter");
		String postId = publishPoll(author, "Duas opcoes", List.of("A", "B"), "poll-bad-opt-1");

		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", voter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":2}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_POLL_OPTION"));

		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", voter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_POLL_OPTION"));
	}

	@Test
	void voteRequiresSession() throws Exception {
		var author = auth.authenticate("user_poll_anon");
		String postId = publishPoll(author, "Sem sessao", List.of("A", "B"), "poll-anon-1");

		mockMvc.perform(post(voteUrl(postId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":0}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsPollWithoutEnoughOptions() throws Exception {
		var author = auth.authenticate("user_poll_few");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-few-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", "Uma so", "pollOptions", List.of("A")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POLL_TOO_FEW_OPTIONS"));
	}

	@Test
	void rejectsPollWithMoreThanSixOptions() throws Exception {
		var author = auth.authenticate("user_poll_many");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-many-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", "Sete",
					"pollOptions", List.of("A", "B", "C", "D", "E", "F", "G")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POLL_TOO_MANY_OPTIONS"));
	}

	@Test
	void rejectsEmptyBlankAndRepeatedOptions() throws Exception {
		var author = auth.authenticate("user_poll_blank");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-blank-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", "Vazia", "pollOptions", List.of("A", "  ")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POLL_EMPTY_OPTION"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-repeat-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", "Repetida",
					"pollOptions", List.of("Java", "java")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POLL_DUPLICATE_OPTION"));
	}

	@Test
	void titleIsLimitedToFourHundredCharacters() throws Exception {
		var author = auth.authenticate("user_poll_title");
		String quatrocentos = "a".repeat(400);
		publishPoll(author, quatrocentos, List.of("A", "B"), "poll-title-ok");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-title-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", "a".repeat(401), "pollOptions", List.of("A", "B")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
	}

	@Test
	void optionsAreIgnoredOutsideAPoll() throws Exception {
		var author = auth.authenticate("user_poll_ignored");
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-ignored-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "INSIGHT", "content", "Post comum",
					"pollOptions", List.of("A", "B")))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// Um post comum nunca ganha enquete — nem por engano do cliente.
		JsonNode poll = readPoll(author, postId);
		assertThat(poll == null || poll.isNull()).isTrue();
	}

	@Test
	void votingOnAPostThatIsNotAPollIsRejected() throws Exception {
		var author = auth.authenticate("user_poll_notpoll");
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "poll-notpoll-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "INSIGHT", "content", "Nao e enquete"))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_A_POLL"));
	}

	@Test
	void pollTravelsInTheFeedPage() throws Exception {
		var author = auth.authenticate("user_poll_feed");
		String postId = publishPoll(author, "Aparece no feed", List.of("Sim", "Nao"), "poll-feed-1");

		MvcResult feed = mockMvc.perform(get("/feed").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode page = objectMapper.readTree(feed.getResponse().getContentAsString());
		JsonNode found = null;
		for (JsonNode post : page.get("items") == null ? page.get("posts") : page.get("items")) {
			if (postId.equals(post.get("id").asText())) {
				found = post;
			}
		}
		assertThat(found).isNotNull();
		assertThat(found.get("poll").get("options")).hasSize(2);
		assertThat(found.get("poll").get("options").get(1).asText()).isEqualTo("Nao");
	}

	private void vote(TestAuthHelper.AuthSession session, String postId, int option) throws Exception {
		mockMvc.perform(post(voteUrl(postId))
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"option\":" + option + "}"))
			.andExpect(status().isOk());
	}

	private JsonNode readPoll(TestAuthHelper.AuthSession session, String postId) throws Exception {
		MvcResult read = mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(read.getResponse().getContentAsString()).get("poll");
	}

	private String publishPoll(TestAuthHelper.AuthSession session, String title, List<String> options, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("type", "POLL", "content", title, "pollOptions", options))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String voteUrl(String postId) {
		return "/feed/posts/" + postId + "/poll/vote";
	}

	private String json(Map<String, Object> body) throws Exception {
		Map<String, Object> payload = new HashMap<>(body);
		payload.putIfAbsent("tags", List.of());
		payload.putIfAbsent("mediaIds", List.of());
		return objectMapper.writeValueAsString(payload);
	}
}
