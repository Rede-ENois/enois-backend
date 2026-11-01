package com.socialcoisa.rede.moderation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * An approved report against a feed comment removes the comment, like it does for a post: the
 * comment leaves the list, the post's comment counter gives it back, and the reviewed content
 * stays readable to the moderation panel (soft delete, so the confirmed report keeps its target).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovedCommentReportTests {

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
	void approvingACommentReportRemovesTheCommentAndKeepsTheCounterConsistent() throws Exception {
		var author = auth.authenticate("acr_author_" + System.nanoTime());
		var commenter = auth.authenticate("acr_commenter_" + System.nanoTime());
		var reporter = auth.authenticate("acr_reporter_" + System.nanoTime());
		var admin = auth.authenticate("group-admin");
		String postId = publish(author);
		String reported = comment(commenter, postId, "Comentario que vai ser denunciado.", null);
		String reply = comment(reporter, postId, "Resposta ao comentario denunciado.", reported);

		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counters.comments").value(2));

		String reportId = report(reporter, reported);
		mockMvc.perform(post("/admin/reports/" + reportId + "/approve")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "acr-approve-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "violacao confirmada"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + reported + "')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + reply + "')]").isNotEmpty());
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counters.comments").value(1));
		mockMvc.perform(get("/admin/reports/" + reportId).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Comentario que vai ser denunciado."));
	}

	@Test
	void banningFromACommentReportRemovesTheCommentAndKeepsTheCounterConsistent() throws Exception {
		var author = auth.authenticate("acr_ban_author_" + System.nanoTime());
		var commenter = auth.authenticate("acr_ban_commenter_" + System.nanoTime());
		var reporter = auth.authenticate("acr_ban_reporter_" + System.nanoTime());
		var admin = auth.authenticate("group-admin");
		String postId = publish(author);
		String reported = comment(commenter, postId, "Comentario de quem vai ser banido.", null);
		comment(reporter, postId, "Resposta que fica no ar.", reported);

		String reportId = report(reporter, reported);
		mockMvc.perform(post("/admin/reports/" + reportId + "/ban").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + reported + "')]").isEmpty());
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counters.comments").value(1));
	}

	private String publish(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "acr-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Post para testar denuncia de comentario.",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content, String parentId)
			throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("content", content);
		payload.put("parentCommentId", parentId);
		payload.put("mediaIds", List.of());
		payload.put("mentionedUserIds", List.of());
		payload.put("website", "");
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "acr-comment-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String report(TestAuthHelper.AuthSession reporter, String commentId) throws Exception {
		MvcResult result = mockMvc.perform(post("/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "acr-report-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"targetType", "COMMENT", "targetId", commentId, "reason", "SPAM",
					"description", "comentario com spam"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}
}
