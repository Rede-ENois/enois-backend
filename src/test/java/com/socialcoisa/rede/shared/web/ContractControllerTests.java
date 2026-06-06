package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void exposesInitialHttpContractsInOpenApi() throws Exception {
		mockMvc.perform(get("/api/openapi.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/network/users']").exists())
			.andExpect(jsonPath("$.paths['/api/media/uploads']").exists())
			.andExpect(jsonPath("$.paths['/api/communities']").exists())
			.andExpect(jsonPath("$.paths['/api/jobs/{jobId}/applications']").exists())
			.andExpect(jsonPath("$.paths['/api/events/{eventId}/attendees']").exists())
			.andExpect(jsonPath("$.paths['/api/mentors/{mentorId}/sessions']").exists())
			.andExpect(jsonPath("$.paths['/api/conversations/{conversationId}/messages']").exists())
			.andExpect(jsonPath("$.paths['/api/notifications/read-all']").exists())
			.andExpect(jsonPath("$.paths['/api/search']").exists());
	}

	@Test
	void returnsStableEmptyPagesForContractLists() throws Exception {
		mockMvc.perform(get("/api/communities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void replaysIdempotentContractWrites() throws Exception {
		MvcResult first = mockMvc.perform(post("/api/jobs/{jobId}/saves", "job_contract")
				.header("X-User-Id", "user_contract")
				.header("Idempotency-Key", "contract-job-save-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SAVED"))
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/jobs/{jobId}/saves", "job_contract")
				.header("X-User-Id", "user_contract")
				.header("Idempotency-Key", "contract-job-save-1"))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}
