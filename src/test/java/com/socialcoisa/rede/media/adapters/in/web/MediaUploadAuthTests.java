package com.socialcoisa.rede.media.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** Diagnostico: upload de midia com conta verificada deve funcionar; sem sessao, 401. */
@SpringBootTest(properties = {"rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://cdn.exemplo.com"})
@AutoConfigureMockMvc
class MediaUploadAuthTests {

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
	void verifiedAccountCanRequestUpload() throws Exception {
		var session = auth.authenticate("media_upload_ok");
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-up-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/foto.jpg", "mimeType", "image/jpeg", "size", 1024,
					"purpose", "AVATAR"))))
			.andExpect(status().isCreated());
	}

	@Test
	void fileAboveTheLimitIsRejectedWithAClearMessage() throws Exception {
		var session = auth.authenticate("media_upload_grande");
		// 501MB de IMAGEM: passa do teto geral de 500MB do serviço (vídeo tem o teto próprio de 10MB).
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-up-grande")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/imagem.png", "mimeType", "image/png",
					"size", 501L * 1024L * 1024L, "purpose", "POST_IMAGE"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("500MB")));
	}

	@Test
	void videoAboveItsOwnLimitIsRejected() throws Exception {
		var session = auth.authenticate("media_video_grande");
		// 10MB + 1 byte de vídeo: o teto por tipo (issue #656) é mais estreito que o geral.
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-up-video-grande")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/video.mp4", "mimeType", "video/mp4",
					"size", 10L * 1024L * 1024L + 1, "purpose", "POST_VIDEO"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10MB")));
	}

	@Test
	void fileUpToTheLimitIsAccepted() throws Exception {
		var session = auth.authenticate("media_upload_no_limite");
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "media-up-no-limite")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/video.mp4", "mimeType", "video/mp4",
					"size", 10L * 1024L * 1024L, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated());
	}

	@Test
	void withoutSessionIsUnauthorized() throws Exception {
		mockMvc.perform(post("/media/uploads")
				.header("Idempotency-Key", "media-up-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/foto.jpg", "mimeType", "image/jpeg", "size", 1024,
					"purpose", "AVATAR"))))
			.andExpect(status().isUnauthorized());
	}
}
