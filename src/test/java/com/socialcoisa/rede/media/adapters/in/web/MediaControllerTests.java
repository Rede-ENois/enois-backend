package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class MediaControllerTests {

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
	void createsMediaUploadAndReplaysIdempotentResponse() throws Exception {
		var session = auth.authenticate("user_media_upload");
		String payload = objectMapper.writeValueAsString(Map.of(
			"fileName", "arquitetura.png",
			"mimeType", "image/png",
			"size", 2048,
			"purpose", "POST_IMAGE"));

		MvcResult first = mockMvc.perform(post("/api/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-upload-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.ownerId").exists())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.deleteStatus").value("NONE"))
			.andExpect(jsonPath("$.uploadUrl").exists())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-upload-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}

	@Test
	void deletesMediaReferencedByDeletedPost() throws Exception {
		var session = auth.authenticate("user_media_feed");
		String mediaPayload = objectMapper.writeValueAsString(Map.of(
			"fileName", "post.png",
			"mimeType", "image/png",
			"size", 4096,
			"purpose", "POST_IMAGE"));

		MvcResult mediaResult = mockMvc.perform(post("/api/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-upload-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaPayload))
			.andExpect(status().isCreated())
			.andReturn();

		String mediaId = objectMapper.readTree(mediaResult.getResponse().getContentAsString()).get("id").asText();
		String postPayload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Midia precisa acompanhar o ciclo de vida do post.",
			"tags", List.of("Media"),
			"mediaIds", List.of(mediaId)));

		MvcResult postResult = mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(postPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mediaIds[0]").value(mediaId))
			.andReturn();

		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(delete("/api/feed/posts/{postId}", postId)
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-delete-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"));

		MvcResult deletedMedia = mockMvc.perform(get("/api/media/{mediaId}", mediaId)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();

		JsonNode node = objectMapper.readTree(deletedMedia.getResponse().getContentAsString());
		assertThat(node.get("deleteStatus").asText()).isIn("REQUESTED", "CDN_PURGED", "OBJECT_DELETED");
	}
}
