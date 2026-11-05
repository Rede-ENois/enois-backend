package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** The report e-mail to support carries user-controlled names and text: all of it must be escaped. */
@SpringBootTest(properties = "rede.support.email=support@test.com")
@AutoConfigureMockMvc
class ReportEmailEscapingTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private EmailService emailService;

	@Test
	void reportEmailEscapesNamesContentAndDescription() throws Exception {
		var reporter = new TestAuthHelper(mockMvc, objectMapper).authenticate("rep_escape");
		jdbc.update("update users set name = ? where id = ?", "<a href=\"https://x.test\">Admin</a>", reporter.userId());

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "rep-escape-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "<b>post</b> text",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(post("/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "rep-escape-report")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("targetType", "POST", "targetId", postId,
					"reason", "SCAM", "description", "<img src=x>"))))
			.andExpect(status().isCreated());

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendHtmlEmail(eq("support@test.com"), anyString(), body.capture());
		assertThat(body.getValue())
			.doesNotContain("<a href=\"https://x.test\"", "<img", "<b>post")
			.contains("&lt;a href=", "&lt;img src=x&gt;");
	}
}
