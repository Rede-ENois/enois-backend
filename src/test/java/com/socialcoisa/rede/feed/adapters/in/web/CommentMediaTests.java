package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Midia nos comentarios (e nas respostas): o comentario pode carregar ate 4 midias do proprio autor,
 * pode ser so de midia (sem texto), e a edicao substitui a midia. Midia de outra pessoa e recusada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentMediaTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private com.socialcoisa.rede.feed.application.port.in.CommentOnPostUseCase commentOnPostUseCase;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Midia "uploadada" pelo autor direto no banco (o fluxo de upload tem teste proprio). */
	private void insertMedia(String mediaId, String ownerId) {
		jdbcTemplate.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose, object_key,
				cdn_url, status, delete_status, created_at, expires_at)
			values (?, ?, 'media/x/foto.png', 'image/png', 1024, 'POST_IMAGE', 'media/x/foto.png',
				'https://cdn.test/foto.png', 'READY', 'NONE', ?, ?)
			""", mediaId, ownerId, java.time.Instant.now(),
			java.time.Instant.now().plusSeconds(3600));
	}

	@Test
	void commentCarriesMediaAndReplyInheritsTheFlow() throws Exception {
		var author = auth.authenticate("cmedia_author");
		insertMedia("cm_media_1", author.userId());

		String postId = publish(author, "Post para comentarios com midia.");

		// Comentario raiz so de midia (sem texto).
		MvcResult root = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-root-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "", "mediaIds", List.of("cm_media_1"), "mentionedUserIds", List.of()))))
			.andReturn();
		if (root.getResponse().getStatus() != 201) {
			System.out.println("DEBUG cmedia root=" + root.getResponse().getStatus()
				+ " body=" + root.getResponse().getContentAsString());
			// Reproduz fora do HTTP para ver a excecao real, nao o handler generico.
			commentOnPostUseCase.comment(new com.socialcoisa.rede.feed.application.CommentOnPostCommand(
				new com.socialcoisa.rede.feed.domain.UserId(author.userId()),
				new com.socialcoisa.rede.feed.domain.PostId(postId), null, "", List.of("cm_media_1"),
				List.of()));
		}
		assertThat(root.getResponse().getStatus())
			.as("criar comentario com midia: " + root.getResponse().getContentAsString()).isEqualTo(201);
		assertThat(objectMapper.readTree(root.getResponse().getContentAsString()).path("mediaIds")
			.get(0).asString()).isEqualTo("cm_media_1");
		String rootId = objectMapper.readTree(root.getResponse().getContentAsString()).get("id").asText();

		// Resposta (a um comentario, nao ao post) tambem carrega midia.
		insertMedia("cm_media_2", author.userId());
		MvcResult reply = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-reply-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "olha o gif", "parentCommentId", rootId,
					"mediaIds", List.of("cm_media_2"), "mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mediaIds[0]").value("cm_media_2"))
			.andReturn();
		String replyId = objectMapper.readTree(reply.getResponse().getContentAsString()).get("id").asText();

		// A listagem devolve as midias de volta.
		MvcResult list = mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		boolean rootHasMedia = false;
		boolean replyHasMedia = false;
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			if (replyId.equals(item.path("id").asString())) {
				replyHasMedia = item.path("mediaIds").size() == 1
					&& "cm_media_2".equals(item.path("mediaIds").get(0).asString());
			}
			if (rootId.equals(item.path("id").asString())) {
				rootHasMedia = item.path("mediaIds").size() == 1
					&& "cm_media_1".equals(item.path("mediaIds").get(0).asString());
			}
		}
		assertThat(rootHasMedia).isTrue();
		assertThat(replyHasMedia).isTrue();
	}

	@Test
	void commentWithoutTextNorMediaIsRejected() throws Exception {
		var author = auth.authenticate("cmedia_author2");
		String postId = publish(author, "Post para recusar comentario vazio.");

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-empty-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "", "mediaIds", List.of(), "mentionedUserIds", List.of()))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void foreignMediaIsRejected() throws Exception {
		var author = auth.authenticate("cmedia_author3");
		var other = auth.authenticate("cmedia_other3");
		insertMedia("cm_media_foreign", other.userId());

		String postId = publish(author, "Post para recusar midia alheia.");

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-foreign-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "roubei a midia", "mediaIds", List.of("cm_media_foreign"),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isForbidden());
	}

	@Test
	void editReplacesMedia() throws Exception {
		var author = auth.authenticate("cmedia_author4");
		insertMedia("cm_media_old", author.userId());
		insertMedia("cm_media_new", author.userId());

		String postId = publish(author, "Post para editar comentario com midia.");

		MvcResult created = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-edit-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "com midia antiga", "mediaIds", List.of("cm_media_old"),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated()).andReturn();
		String commentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// mediaIds null (campo ausente) mantem a midia; lista substitui.
		MvcResult edited = mockMvc.perform(patch("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-edit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "texto sem mexer na midia"))))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(edited.getResponse().getContentAsString()).path("mediaIds")
			.get(0).asString()).isEqualTo("cm_media_old");

		MvcResult replaced = mockMvc.perform(patch("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "cmedia-edit-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "so texto agora", "mediaIds", List.of("cm_media_new")))))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode node = objectMapper.readTree(replaced.getResponse().getContentAsString());
		assertThat(node.path("mediaIds").size()).isEqualTo(1);
		assertThat(node.path("mediaIds").get(0).asString()).isEqualTo("cm_media_new");
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "cmedia-pub-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
