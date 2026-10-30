package com.socialcoisa.rede.blog.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.blog.application.port.out.BlogPostRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * The blog directory: post counts per blog, banned authors left out, and both answered for the whole
 * page at once instead of one query per blog.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlogDirectoryTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SanctionRepository sanctions;

	@MockitoSpyBean
	private BlogPostRepository posts;

	@MockitoSpyBean
	private SanctionGateReader sanctionGate;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String saveBlog(TestAuthHelper.AuthSession author, String title) throws Exception {
		MvcResult saved = mockMvc.perform(put("/blogs/me")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "dir-blog-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", title, "description", "Diretorio"))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(saved.getResponse().getContentAsString()).get("slug").asText();
	}

	private void publish(TestAuthHelper.AuthSession author, String title) throws Exception {
		mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "dir-post-" + title + "-" + author.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("title", title, "body", "Texto do post " + title, "format", "MARKDOWN"))))
			.andExpect(status().isCreated());
	}

	@Test
	void directoryCountsPostsAndDropsBannedAuthorsWithoutPerBlogLookups() throws Exception {
		var writer = auth.authenticate("dir_writer");
		var banned = auth.authenticate("dir_banned");
		String writerSlug = saveBlog(writer, "Blog com dois posts");
		publish(writer, "Um");
		publish(writer, "Dois");
		String bannedSlug = saveBlog(banned, "Blog de conta banida");
		sanctions.save(UserSanction.permaBan(UUID.randomUUID().toString(), banned.userId(), null, "teste",
			Instant.now()));

		clearInvocations(posts, sanctionGate);
		mockMvc.perform(get("/blogs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.slug == '" + writerSlug + "')].posts").value(2))
			.andExpect(jsonPath("$[?(@.slug == '" + bannedSlug + "')]").isEmpty());
		verify(posts, never()).countByBlog(anyString());
		verify(sanctionGate, never()).banned(anyString());
	}
}
