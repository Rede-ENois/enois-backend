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

		mockMvc.perform(get("/api/network/suggestions")
				.header("X-Session-Token", sessionA.token())
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(post("/api/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-req-" + sessionA.userId()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));

		mockMvc.perform(get("/api/network/requests")
				.header("X-Session-Token", sessionB.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].fromUserId").value(sessionA.userId()));

		mockMvc.perform(put("/api/network/connections/{target}/accept", sessionA.userId())
				.header("X-Session-Token", sessionB.token())
				.header("Idempotency-Key", "net-acc-" + sessionA.userId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		mockMvc.perform(get("/api/network/summary")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/api/network/summary")
				.header("X-Session-Token", sessionB.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/api/network/suggestions")
				.header("X-Session-Token", sessionA.token())
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(get("/api/network/users")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].connected").value(true));

		mockMvc.perform(delete("/api/network/connections/{target}", sessionB.userId())
				.header("X-Session-Token", sessionA.token())
				.header("Idempotency-Key", "net-rem-" + sessionA.userId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		mockMvc.perform(get("/api/network/summary")
				.header("X-Session-Token", sessionA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(0));
	}

	@Test
	void replaysIdempotentConnectionRequest() throws Exception {
		var targetSession = auth.authenticate("net_idem_target_" + System.nanoTime());
		var session = auth.authenticate("net_idem_user");

		MvcResult first = mockMvc.perform(post("/api/network/connections/{target}", targetSession.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/network/connections/{target}", targetSession.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}
