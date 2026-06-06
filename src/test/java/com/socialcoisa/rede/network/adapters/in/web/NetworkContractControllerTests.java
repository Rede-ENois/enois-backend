package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class NetworkContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void suggestsRequestsAcceptsAndRemovesConnection() throws Exception {
		String a = "net_user_a_" + System.nanoTime();
		String b = "net_user_b_" + System.nanoTime();

		mockMvc.perform(get("/api/network/suggestions")
				.header("X-User-Id", a)
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(post("/api/network/connections/{target}", b)
				.header("X-User-Id", a)
				.header("Idempotency-Key", "net-req-" + a))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("REQUESTED"));

		mockMvc.perform(get("/api/network/requests")
				.header("X-User-Id", b))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].fromUserId").value(a));

		mockMvc.perform(put("/api/network/connections/{target}/accept", a)
				.header("X-User-Id", b)
				.header("Idempotency-Key", "net-acc-" + a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		mockMvc.perform(get("/api/network/summary")
				.header("X-User-Id", a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/api/network/summary")
				.header("X-User-Id", b))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(1));

		mockMvc.perform(get("/api/network/suggestions")
				.header("X-User-Id", a)
				.param("limit", "4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray());

		mockMvc.perform(get("/api/network/users")
				.header("X-User-Id", a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].connected").value(true));

		mockMvc.perform(delete("/api/network/connections/{target}", b)
				.header("X-User-Id", a)
				.header("Idempotency-Key", "net-rem-" + a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		mockMvc.perform(get("/api/network/summary")
				.header("X-User-Id", a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connections").value(0));
	}

	@Test
	void replaysIdempotentConnectionRequest() throws Exception {
		String target = "net_idem_target_" + System.nanoTime();

		MvcResult first = mockMvc.perform(post("/api/network/connections/{target}", target)
				.header("X-User-Id", "net_idem_user")
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/network/connections/{target}", target)
				.header("X-User-Id", "net_idem_user")
				.header("Idempotency-Key", "net-idem-req"))
			.andExpect(status().isAccepted())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}
