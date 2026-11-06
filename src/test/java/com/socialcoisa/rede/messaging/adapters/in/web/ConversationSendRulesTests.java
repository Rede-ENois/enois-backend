package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

/** Blocks and minor protection follow the current state on every send, not the state at creation. */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationSendRulesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MessagingService messaging;

	@Autowired
	private ConnectionRepository connections;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;
	private String suffix;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		suffix = String.valueOf(System.nanoTime());
	}

	@Test
	void blockByTheRecipientAfterTheDmExistsStopsTheSender() throws Exception {
		AuthSession sender = auth.authenticate("csr_sender_" + suffix);
		AuthSession recipient = auth.authenticate("csr_recipient_" + suffix);
		String conversationId = openDirect(sender, recipient);
		send(sender, conversationId, "antes").andExpect(status().isCreated());

		block(recipient, sender);

		send(sender, conversationId, "depois").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("USER_BLOCKED"));
	}

	@Test
	void blockBySenderAfterTheDmExistsStopsBothSides() throws Exception {
		AuthSession sender = auth.authenticate("csr_blocker_" + suffix);
		AuthSession recipient = auth.authenticate("csr_target_" + suffix);
		String conversationId = openDirect(sender, recipient);

		block(sender, recipient);

		send(sender, conversationId, "oi").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("USER_BLOCKED"));
		send(recipient, conversationId, "oi").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("USER_BLOCKED"));
	}

	@Test
	void jobConversationFollowsALaterBlockOnSendAndOnReopen() throws Exception {
		AuthSession candidate = auth.authenticate("csr_candidate_" + suffix);
		AuthSession employer = auth.authenticate("csr_employer_" + suffix);
		String conversationId = messaging.directConversationBetween(candidate.userId(), employer.userId(), "JOB");
		messaging.sendMessage(conversationId, candidate.userId(), "candidatura");

		block(employer, candidate);

		assertThatThrownBy(() -> messaging.sendMessage(conversationId, candidate.userId(), "de novo"))
			.isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions
				.assertThat(e.code()).isEqualTo("USER_BLOCKED"));
		assertThatThrownBy(() -> messaging.directConversationBetween(candidate.userId(), employer.userId(), "JOB"))
			.isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions
				.assertThat(e.code()).isEqualTo("USER_BLOCKED"));
	}

	@Test
	void minorIsUnreachableOnceTheConnectionIsRemoved() throws Exception {
		AuthSession adult = auth.authenticate("csr_adult_" + suffix);
		AuthSession minor = auth.authenticate("csr_minor_" + suffix);
		connections.save(UserConnection.restore(null, adult.userId(), minor.userId(), ConnectionStatus.ACCEPTED,
			Instant.now(), Instant.now(), null));
		jdbc.update("UPDATE users SET birth_date = ? WHERE id = ?",
			LocalDate.now(ZoneId.of("America/Sao_Paulo")).minusYears(15).toString(), minor.userId());
		String conversationId = openDirect(adult, minor);
		send(adult, conversationId, "oi").andExpect(status().isCreated());

		mockMvc.perform(delete("/network/connections/{target}", minor.userId())
				.header("X-Session-Token", adult.token())
				.header("Idempotency-Key", "csr-unlink-" + suffix))
			.andExpect(status().is2xxSuccessful());

		send(adult, conversationId, "ainda ai?").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MINOR_DM_RESTRICTED"));
		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", adult.token())
				.header("Idempotency-Key", "csr-reopen-" + suffix)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", minor.userId()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MINOR_DM_RESTRICTED"));
	}

	@Test
	void blockBetweenTwoMembersDoesNotSilenceTheGroup() throws Exception {
		AuthSession creator = auth.authenticate("csr_gcreator_" + suffix);
		AuthSession member = auth.authenticate("csr_gmember_" + suffix);
		AuthSession other = auth.authenticate("csr_gother_" + suffix);
		String conversationId = objectMapper.readTree(mockMvc.perform(post("/conversations")
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "csr-group-" + suffix)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "GROUP", "title", "Grupo",
					"participantIds", List.of(member.userId(), other.userId())))))
			.andExpect(status().is2xxSuccessful())
			.andReturn().getResponse().getContentAsString()).get("id").asString();

		block(member, other);

		send(creator, conversationId, "oi grupo").andExpect(status().isCreated());
	}

	private String openDirect(AuthSession from, AuthSession to) throws Exception {
		return objectMapper.readTree(mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", from.token())
				.header("Idempotency-Key", "csr-open-" + from.userId() + to.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", to.userId()))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("id").asString();
	}

	private ResultActions send(AuthSession from, String conversationId, String text) throws Exception {
		return mockMvc.perform(post("/conversations/{id}/messages", conversationId)
			.header("X-Session-Token", from.token())
			.header("Idempotency-Key", "csr-send-" + from.userId() + text.hashCode())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("text", text))));
	}

	private void block(AuthSession blocker, AuthSession target) throws Exception {
		mockMvc.perform(post("/network/blocks/{target}", target.userId())
				.header("X-Session-Token", blocker.token())
				.header("Idempotency-Key", "csr-block-" + blocker.userId() + target.userId()))
			.andExpect(status().isCreated());
	}
}
