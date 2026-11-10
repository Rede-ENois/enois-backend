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

@SpringBootTest(properties = {"rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://cdn.exemplo.com"})
@AutoConfigureMockMvc
class MediaControllerTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}


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
	void acceptsMediaUpTo500MbAndRejectsAboveIt() throws Exception {
		var session = auth.authenticate("user_media_500mb");

		String atLimit = objectMapper.writeValueAsString(Map.of(
			"fileName", "video-grande.mp4",
			"mimeType", "video/mp4",
			"size", 500L * 1024L * 1024L,
			"purpose", "POST_VIDEO"));

		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-upload-500mb")
				.contentType(MediaType.APPLICATION_JSON)
				.content(atLimit))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploadUrl").exists());

		String aboveLimit = objectMapper.writeValueAsString(Map.of(
			"fileName", "video-grande-demais.mp4",
			"mimeType", "video/mp4",
			"size", 500L * 1024L * 1024L + 1,
			"purpose", "POST_VIDEO"));

		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-upload-500mb-plus")
				.contentType(MediaType.APPLICATION_JSON)
				.content(aboveLimit))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"));
	}

	@Test
	void badgeAcceptsGifUpToTwoMbAndRejectsAboveIt() throws Exception {
		var session = auth.authenticate("user_media_badge_gif");

		// GIF animado entra (o proposito BADGE aceita image/gif e o sanitizador preserva a animacao).
		String gifAtLimit = objectMapper.writeValueAsString(Map.of(
			"fileName", "emblema-animado.gif",
			"mimeType", "image/gif",
			"size", 2L * 1024L * 1024L,
			"purpose", "BADGE"));
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "badge-gif-at-limit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(gifAtLimit))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploadUrl").exists());

		// Emblema tem teto PROPRIO (2MB), bem abaixo do teto geral de midia: um "favicon" de 500MB
		// nao passa mais.
		String gifAboveLimit = objectMapper.writeValueAsString(Map.of(
			"fileName", "emblema-pesado.gif",
			"mimeType", "image/gif",
			"size", 2L * 1024L * 1024L + 1,
			"purpose", "BADGE"));
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "badge-gif-above-limit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(gifAboveLimit))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"));

		// O mesmo tamanho que e aceito como POST_VIDEO segue valendo: o teto e por proposito.
		String videoSameSize = objectMapper.writeValueAsString(Map.of(
			"fileName", "outro.mp4",
			"mimeType", "video/mp4",
			"size", 3L * 1024L * 1024L,
			"purpose", "POST_VIDEO"));
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "badge-gif-video-3mb")
				.contentType(MediaType.APPLICATION_JSON)
				.content(videoSameSize))
			.andExpect(status().isCreated());
	}

	@Test
	void createsMediaUploadAndReplaysIdempotentResponse() throws Exception {
		var session = auth.authenticate("user_media_upload");
		String payload = objectMapper.writeValueAsString(Map.of(
			"fileName", "arquitetura.png",
			"mimeType", "image/png",
			"size", 2048,
			"purpose", "POST_IMAGE"));

		MvcResult first = mockMvc.perform(post("/media/uploads")
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

		MvcResult replay = mockMvc.perform(post("/media/uploads")
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

		MvcResult mediaResult = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-upload-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaPayload))
			.andExpect(status().isCreated())
			.andReturn();

		String mediaId = objectMapper.readTree(mediaResult.getResponse().getContentAsString()).get("id").asText();
		String postPayload = objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT",
			"content", "Midia precisa acompanhar o ciclo de vida do post.",
			"tags", List.of("Media"),
			"mediaIds", List.of(mediaId)));

		MvcResult postResult = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(postPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mediaIds[0]").value(mediaId))
			.andReturn();

		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(delete("/feed/posts/{postId}", postId)
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-feed-delete-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"));

		MvcResult deletedMedia = mockMvc.perform(get("/media/{mediaId}", mediaId)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();

		JsonNode node = objectMapper.readTree(deletedMedia.getResponse().getContentAsString());
		assertThat(node.get("deleteStatus").asText()).isIn("REQUESTED", "CDN_PURGED", "OBJECT_DELETED");
	}

	@Test
	void rejectsActiveContentTypesEvenWhenTheyClaimToBeImages() throws Exception {
		var session = auth.authenticate("user_media_mime");

		for (String mime : List.of("text/html", "image/svg+xml", "application/javascript")) {
			String payload = objectMapper.writeValueAsString(Map.of(
				"fileName", "arquivo.html",
				"mimeType", mime,
				"size", 1024,
				"purpose", "POST_IMAGE"));

			mockMvc.perform(post("/media/uploads")
					.header("X-Session-Token", session.token())
					.header("Idempotency-Key", "media-mime-" + mime)
					.contentType(MediaType.APPLICATION_JSON)
					.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));
		}
	}
}
