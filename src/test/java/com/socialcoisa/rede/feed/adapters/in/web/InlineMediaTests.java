package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Item 9 (Game Dev): imagem no meio do texto. O marcador {{media:id}} é permitido somente
 * quando a mídia é anexo da MESMA publicação — marcador solto apontando para mídia alheia ou
 * inexistente é recusado com 400, nunca exibido como se fosse do autor. No comentário vale o
 * mesmo, e o post correto continua entrando no ar com o conteúdo intacto (o marcador é texto).
 */
@SpringBootTest
@AutoConfigureMockMvc
class InlineMediaTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void markerReferencingOwnAttachmentIsAccepted() throws Exception {
		var author = auth.authenticate("inline_author");
		// A mídia anexada precisa existir e ser do autor (validação de posse do serviço).
		jdbcTemplate.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose, object_key,
				cdn_url, status, delete_status, created_at, expires_at)
			values (?, ?, 'media/x/foto.png', 'image/png', 1024, 'POST_IMAGE', 'media/x/foto.png',
				'https://cdn.test/foto.png', 'READY', 'NONE', ?, ?)
			""", "media_autor_1", author.userId(), java.time.Instant.now(),
			java.time.Instant.now().plusSeconds(3600));
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-ok-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", "Explicando a ideia:\n\n{{media:media_autor_1}}\n\nE o resultado.",
					"tags", List.of(), "mediaIds", List.of("media_autor_1")))))
			.andExpect(status().isCreated())
			// O marcador é texto: atravessa a criação intacto e volta no conteúdo.
			.andExpect(jsonPath("$.content")
				.value("Explicando a ideia:\n\n{{media:media_autor_1}}\n\nE o resultado."));
	}

	@Test
	void markerReferencingForeignMediaIsRejected() throws Exception {
		var author = auth.authenticate("inline_foreign");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-foreign-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Usando a mídia dos outros: {{media:media_alheia}}",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INLINE_MEDIA"));
	}

	@Test
	void commentMarkerAlsoNeedsTheAttachment() throws Exception {
		var author = auth.authenticate("inline_comment_author");
		// Mídia "uploadada" pelo autor direto no banco (o fluxo de upload tem teste próprio).
		jdbcTemplate.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose, object_key,
				cdn_url, status, delete_status, created_at, expires_at)
			values (?, ?, 'media/x/foto.png', 'image/png', 1024, 'POST_IMAGE', 'media/x/foto.png',
				'https://cdn.test/foto.png', 'READY', 'NONE', ?, ?)
			""", "inline_media_1", author.userId(), java.time.Instant.now(),
			java.time.Instant.now().plusSeconds(3600));
		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "post para comentário", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();

		// Marcador sem anexo no comentário: recusado. Com anexo: entra.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-comment-bad-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"content", "veja {{media:x}}", "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INLINE_MEDIA"));

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-comment-ok-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"content", "veja {{media:inline_media_1}}", "mediaIds", List.of("inline_media_1")))))
			.andExpect(status().isCreated());
	}

	@Test
	void postWithoutMarkersDoesNotValidateAnything() throws Exception {
		var author = auth.authenticate("inline_none");
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "inline-none-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "post comum sem marcador", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}
}
