package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class NetworkContractControllerTests {

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
	void suggestsRequestsAcceptsAndRemovesConnection() throws Exception {
		var sessionA = auth.authenticate("net_user_a_" + System.nanoTime());
		var sessionB = auth.authenticate("net_user_b_" + System.nanoTime());

		mockMvc.perform(get("/network/suggestions")
				.header("X-Session-Token", sessionA.token())
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(post("/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-req-" + sessionA.userId()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));

		mockMvc.perform(get("/network/requests")
				.header("X-Session-Token", sessionB.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].fromUserId").value(sessionA.userId()));

		mockMvc.perform(put("/network/connections/{target}/accept", sessionA.userId())
				.header("X-Session-Token", sessionB.token())
				.header("Idempotency-Key", "net-acc-" + sessionA.userId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		String usernameA = objectMapper.readTree(mockMvc.perform(get("/me")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("username").asText();
		String usernameB = objectMapper.readTree(mockMvc.perform(get("/me")
				.header("X-Session-Token", sessionB.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("username").asText();

		mockMvc.perform(get("/users/{username}/connections", usernameA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(sessionB.userId()))
			.andExpect(jsonPath("$[0].username").value(usernameB));

		mockMvc.perform(get("/users/{username}/followers", usernameA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());

		mockMvc.perform(get("/users/{username}/followers", usernameB))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(sessionA.userId()))
			.andExpect(jsonPath("$[0].username").value(usernameA));

		mockMvc.perform(get("/network/summary")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/network/summary")
				.header("X-Session-Token", sessionB.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/network/suggestions")
				.header("X-Session-Token", sessionA.token())
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(get("/network/users")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].connected").value(true));

		mockMvc.perform(delete("/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-rem-" + sessionA.userId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		mockMvc.perform(get("/network/summary")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(0));

		mockMvc.perform(get("/users/{username}/connections", usernameA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void statusTellsWhoRequestedThePendingConnection() throws Exception {
		var sessionA = auth.authenticate("net_dir_a_" + System.nanoTime());
		var sessionB = auth.authenticate("net_dir_b_" + System.nanoTime());

		assertStatus(sessionA, sessionB, "NONE", false);

		mockMvc.perform(post("/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-dir-req-" + sessionA.userId()))
			.andExpect(status().isAccepted());

		// O mesmo PENDING para os dois lados: sem a direção, quem recebeu via "cancelar" e recusava.
		assertStatus(sessionA, sessionB, "PENDING", true);
		assertStatus(sessionB, sessionA, "PENDING", false);

		mockMvc.perform(put("/network/connections/{target}/accept", sessionA.userId())
				.header("X-Session-Token", sessionB.token())
				.header("Idempotency-Key", "net-dir-acc-" + sessionA.userId()))
			.andExpect(status().isOk());

		assertStatus(sessionA, sessionB, "ACCEPTED", false);
		assertStatus(sessionB, sessionA, "ACCEPTED", false);

		mockMvc.perform(delete("/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-dir-rem-" + sessionA.userId()))
			.andExpect(status().isOk());

		assertStatus(sessionA, sessionB, "REMOVED", false);

		// Reabrir reaproveita a mesma linha: a direção tem de acompanhar quem pediu desta vez.
		mockMvc.perform(post("/network/connections/{target}", sessionA.userId())
				.header("X-Session-Token", sessionB.token())
				.header("Idempotency-Key", "net-dir-reopen-" + sessionB.userId()))
			.andExpect(status().isAccepted());

		assertStatus(sessionB, sessionA, "PENDING", true);
		assertStatus(sessionA, sessionB, "PENDING", false);
	}

	private void assertStatus(TestAuthHelper.AuthSession viewer, TestAuthHelper.AuthSession target,
			String expectedStatus, boolean expectedRequestedByMe) throws Exception {
		mockMvc.perform(get("/network/connections/{target}/status", target.userId())
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.requestedByMe").value(expectedRequestedByMe));
	}

	@Test
	void removedOrBlockedConnectionCanBeRequestedAgainAfterUnblocking() throws Exception {
		var a = auth.authenticate("net_reconnect_a_" + System.nanoTime());
		var b = auth.authenticate("net_reconnect_b_" + System.nanoTime());

		// Conecta e aceita.
		mockMvc.perform(post("/network/connections/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "net-rc-connect"))
			.andExpect(status().isAccepted());
		mockMvc.perform(put("/network/connections/{target}/accept", a.userId())
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "net-rc-accept"))
			.andExpect(status().isOk());

		// Remover (o "recusar" usa o mesmo endpoint) não impede a nova solicitação.
		mockMvc.perform(delete("/network/connections/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "net-rc-remove"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/network/connections/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "net-rc-again"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));

		// Aceita, bloqueia e confirma o 403 enquanto o bloqueio existir.
		mockMvc.perform(put("/network/connections/{target}/accept", a.userId())
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "net-rc-accept-2"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/network/blocks/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "net-rc-block"))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/network/connections/{target}", a.userId())
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "net-rc-blocked"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BLOCKED"));

		// Depois de desbloquear, a conexão volta a ser possível.
		mockMvc.perform(delete("/network/blocks/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "net-rc-unblock"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/network/connections/{target}", a.userId())
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "net-rc-unblocked"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));
	}

	@Test
	void requestsCarryTheOtherSideAndHideBlockedPeople() throws Exception {
		var me = auth.authenticate("net_req_me_" + System.nanoTime());
		var asker = auth.authenticate("net_req_asker_" + System.nanoTime());
		var asked = auth.authenticate("net_req_asked_" + System.nanoTime());
		var blocked = auth.authenticate("net_req_blocked_" + System.nanoTime());

		requestConnection(asker, me);
		requestConnection(me, asked);
		requestConnection(blocked, me);
		mockMvc.perform(post("/network/blocks/{target}", blocked.userId())
				.header("X-Session-Token", me.token())
				.header("Idempotency-Key", "net-req-block-" + me.userId()))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/network/requests").header("X-Session-Token", me.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			// Newest first: the request I sent came after the one I received.
			.andExpect(jsonPath("$.items[0].direction").value("OUTGOING"))
			.andExpect(jsonPath("$.items[0].peer.id").value(asked.userId()))
			.andExpect(jsonPath("$.items[0].peer.username").isNotEmpty())
			.andExpect(jsonPath("$.items[1].direction").value("INCOMING"))
			.andExpect(jsonPath("$.items[1].peer.id").value(asker.userId()));

		// The badge counts only what I can answer: not what I sent, not a blocked person.
		mockMvc.perform(get("/network/summary").header("X-Session-Token", me.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pendingRequests").value(1));
	}

	@Test
	void suggestsFriendsOfFriendsFirstWithTheMutualCount() throws Exception {
		var me = auth.authenticate("net_sug_me_" + System.nanoTime());
		var friend = auth.authenticate("net_sug_friend_" + System.nanoTime());
		var otherFriend = auth.authenticate("net_sug_friend2_" + System.nanoTime());
		var twoInCommon = auth.authenticate("net_sug_two_" + System.nanoTime());
		var oneInCommon = auth.authenticate("net_sug_one_" + System.nanoTime());
		connect(me, friend);
		connect(me, otherFriend);
		connect(twoInCommon, friend);
		connect(twoInCommon, otherFriend);
		connect(oneInCommon, friend);

		// The shared H2 base has many other users: they must not push these two out of the list.
		mockMvc.perform(get("/network/suggestions")
				.header("X-Session-Token", me.token())
				.param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(twoInCommon.userId()))
			.andExpect(jsonPath("$[0].mutualConnections").value(2))
			.andExpect(jsonPath("$[1].id").value(oneInCommon.userId()))
			.andExpect(jsonPath("$[1].mutualConnections").value(1));
	}

	private void connect(TestAuthHelper.AuthSession from, TestAuthHelper.AuthSession to) throws Exception {
		requestConnection(from, to);
		mockMvc.perform(put("/network/connections/{target}/accept", from.userId())
				.header("X-Session-Token", to.token())
				.header("Idempotency-Key", "net-acc-" + from.userId() + "-" + to.userId()))
			.andExpect(status().isOk());
	}

	private void requestConnection(TestAuthHelper.AuthSession from, TestAuthHelper.AuthSession to) throws Exception {
		mockMvc.perform(post("/network/connections/{target}", to.userId())
				.header("X-Session-Token", from.token())
				.header("Idempotency-Key", "net-req-" + from.userId() + "-" + to.userId()))
			.andExpect(status().isAccepted());
	}

	@Test
	void replaysIdempotentConnectionRequest() throws Exception {
		var targetSession = auth.authenticate("net_idem_target_" + System.nanoTime());
		var session = auth.authenticate("net_idem_user");

		MvcResult first = mockMvc.perform(post("/network/connections/{target}", targetSession.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/network/connections/{target}", targetSession.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}
