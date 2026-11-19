package com.socialcoisa.rede.blog.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Vlog (issue "Postar vídeos no blog"): o post do blog aceita mídia do autor — upload pelo
 * fluxo de sempre (S3 fake configurado na classe, como o fluxo real exige), associação com o
 * artigo, leitura devolve os ids para o player do DS. Teto de 8, posse validada.
 */
@SpringBootTest(properties = {"rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://cdn.exemplo.com"})
@AutoConfigureMockMvc
class BlogVlogTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

	private String saveBlog(TestAuthHelper.AuthSession owner, String title, String description) throws Exception {
		var body = new java.util.HashMap<String, Object>();
		body.put("title", title);
		if (description != null) body.put("description", description);
		MvcResult saved = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.put("/blogs/me")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "vlog-blog-" + title)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(saved.getResponse().getContentAsString()).get("slug").asText();
	}

	/**
	 * Vlog (issue "Postar vídeos no blog"): o post aceita mídia do autor — upload pelo fluxo de
	 * sempre, associação com o artigo, leitura devolve os ids para o player do DS. Teto de 8,
	 * posse validada, e post sem mídia continua como sempre foi.
	 */
	@Test
	void blogPostAceitaMidiaVlog() throws Exception {
		var author = auth.authenticate("blog_vlog_author");
		String slug = saveBlog(author, "Vlog do cantinho", "video e texto");

		String media1 = uploadMedia(author, "blog-vlog-m1");
		String media2 = uploadMedia(author, "blog-vlog-m2");

		MvcResult created = mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-vlog-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Meu primeiro vlog", "body",
					"Falando do mutirão em vídeo.", "format", "MARKDOWN", "mediaIds", java.util.List.of(media1, media2)))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mediaIds.length()").value(2))
			.andExpect(jsonPath("$.mediaIds[0]").value(media1))
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// A leitura (página do blog) devolve as mídias para o player.
		mockMvc.perform(get("/blogs/" + slug))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].mediaIds.length()").value(2));

		// Editar troca a mídia (remove uma, mantém a outra).
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/blogs/me/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-vlog-edit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Meu primeiro vlog", "body",
					"Falando do mutirão em vídeo.", "format", "MARKDOWN", "mediaIds", java.util.List.of(media2)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mediaIds.length()").value(1))
			.andExpect(jsonPath("$.mediaIds[0]").value(media2));

		// Mídia de OUTRA pessoa recusa: posse validada.
		var outra = auth.authenticate("blog_vlog_outra");
		String mediaAlheia = uploadMedia(outra, "blog-vlog-alheia");
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/blogs/me/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-vlog-alheia")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Meu primeiro vlog", "body", "x",
					"format", "MARKDOWN", "mediaIds", java.util.List.of(mediaAlheia)))))
			.andExpect(status().isForbidden());
	}

	/** Teto de 8 mídias por post. */
	@Test
	void blogPostRecusaMaisDeOitoMidias() throws Exception {
		var author = auth.authenticate("blog_vlog_limite");
		saveBlog(author, "Vlog limite", "limite");
		var mediaIds = new java.util.ArrayList<String>();
		for (int i = 0; i < 9; i++) {
			mediaIds.add(uploadMedia(author, "blog-vlog-lim-" + i));
		}

		mockMvc.perform(post("/blogs/me/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "blog-vlog-limite")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Muitas midias", "body", "x",
					"format", "MARKDOWN", "mediaIds", mediaIds))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("POST_TOO_MANY_MEDIA"));
	}

	private String uploadMedia(TestAuthHelper.AuthSession owner, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "vlog.mp4", "mimeType", "video/mp4", "size", 2048, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
