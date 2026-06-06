package com.socialcoisa.rede.search.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SearchContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void searchesByKeyword() throws Exception {
		mockMvc.perform(get("/api/search")
				.param("q", "design")
				.param("types", "users,communities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void returnsEmptyForGibberish() throws Exception {
		mockMvc.perform(get("/api/search")
				.param("q", "zzzzzznotfound")
				.param("types", "users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}
}
