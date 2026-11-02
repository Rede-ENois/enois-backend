package com.socialcoisa.rede.coin.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.coin.application.port.out.UserStreakRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** Presence is a side effect: when recording it fails, the post is still published. */
@SpringBootTest
@AutoConfigureMockMvc
class StreakPresenceFailureTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private UserStreakRepository streaks;

	@Test
	void failingPresenceDoesNotBreakPosting() throws Exception {
		when(streaks.find(anyString())).thenThrow(new IllegalStateException("streak store down"));
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("streak_fail_poster");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "streak-fail-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Post que precisa sair mesmo assim.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}
}
