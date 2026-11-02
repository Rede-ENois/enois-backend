package com.socialcoisa.rede.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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

import tools.jackson.databind.ObjectMapper;

/**
 * Mass assignment: o cliente manda campo de SERVIDOR no corpo da requisição e o servidor nao pode
 * obedecer. Autor, dono, contadores e data sao do servidor — se o corpo pudesse escolher quem
 * "publicou", qualquer conta publicaria em nome de outra.
 *
 * Este teste era um dos que faltavam no plano de testes adversariais (BACKLOG, item "CI de
 * seguranca + testes adversariais"). O corpo leva os campos forjados; o que importa NAO e o
 * codigo de erro (o Jackson pode recusar o campo ou ignora-lo — as duas sao aceitacoes), e sim
 * que a autoria, a data e os contadores continuem sendo os do servidor e da sessao.
 */
@SpringBootTest(properties = {"rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://cdn.exemplo.com"})
@AutoConfigureMockMvc
class MassAssignmentTests {

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

	/** Campos que o SERVIDOR controla: se vierem no corpo, ninguem pode escolher o autor. */
	private Map<String, Object> corpoComAutorForjado(String conteudo) {
		Map<String, Object> corpo = new HashMap<>(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT",
			"content", conteudo,
			"tags", List.of(),
			"mediaIds", List.of()));
		// Tudo o que um cliente tentaria forjar para publicar como outra pessoa:
		corpo.put("authorId", "user_da_vitima");
		corpo.put("authorUsername", "vitima");
		corpo.put("userId", "user_da_vitima");
		corpo.put("createdBy", "user_da_vitima");
		corpo.put("ownerId", "user_da_vitima");
		// E os campos de servidor que nao podem vir do cliente:
		corpo.put("createdAt", "1999-01-01T00:00:00Z");
		corpo.put("counters", Map.of("reactions", 999, "comments", 999, "saves", 999, "shares", 999));
		return corpo;
	}

	@Test
	void publicacaoForjadaContinuaSendoDeQuemAssina() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("mass-post-author");

		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mass-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(corpoComAutorForjado("Post com autor forjado no corpo."))))
			.andExpect(status().isCreated())
			.andReturn();

		String postId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			// A autoria e a da sessao, nao a que veio no corpo.
			.andExpect(jsonPath("$.authorId").value(author.userId()))
			.andExpect(jsonPath("$.authorUsername").value("mass-post-author"))
			// E os contadores comecam em zero: o corpo nao decide quanto a publicacao tem.
			.andExpect(jsonPath("$.counters.reactions").value(0))
			.andExpect(jsonPath("$.counters.comments").value(0))
			.andExpect(jsonPath("$.counters.saves").value(0))
			.andExpect(jsonPath("$.counters.shares").value(0));
	}

	@Test
	void comentarioForjadoContinuaSendoDeQuemComenta() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("mass-comment-author");
		TestAuthHelper.AuthSession terceiro = auth.authenticate("mass_comment_terceiro");

		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", terceiro.token())
				.header("Idempotency-Key", "mass-post-base-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post para receber comentario.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();

		Map<String, Object> corpo = new HashMap<>(Map.of("content", "Comentario com autor forjado."));
		corpo.put("authorId", terceiro.userId());
		corpo.put("userId", terceiro.userId());
		corpo.put("authorUsername", "mass_comment_terceiro");

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mass-comment-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(corpo)))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].authorId").value(author.userId()))
			.andExpect(jsonPath("$.items[0].authorUsername").value("mass-comment-author"));
	}

	@Test
	void reacaoForjadaContinuaSendoDeQuemReage() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("mass_react_author");

		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mass-post-react-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post para reagir.", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();

		Map<String, Object> corpo = new HashMap<>(Map.of("type", "LIKE"));
		corpo.put("userId", "user_da_vitima");
		corpo.put("authorId", "user_da_vitima");

		mockMvc.perform(post("/feed/posts/" + postId + "/reactions")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mass-react-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(corpo)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + postId + "/reactions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reactions[0].userId").value(author.userId()));
	}

	@Test
	void midiaDeTerceiroNaoEntraNaPublicacaoDeOutro() throws Exception {
		TestAuthHelper.AuthSession donoDaMidia = auth.authenticate("mass_media_dono");
		TestAuthHelper.AuthSession outro = auth.authenticate("mass_media_outro");

		MvcResult upload = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", donoDaMidia.token())
				.header("Idempotency-Key", "mass-media-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "media/abc/foto.jpg", "mimeType", "image/jpeg", "size", 1024,
					"purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();
		String mediaId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

		// O outro tenta publicar com a midia alheia (e ainda tenta forjar o dono no corpo).
		Map<String, Object> corpo = new HashMap<>(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT", "content", "Post com midia de terceiro.", "tags", List.of(),
			"mediaIds", List.of(mediaId)));
		corpo.put("ownerId", donoDaMidia.userId());
		corpo.put("authorId", donoDaMidia.userId());

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "mass-post-midia-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(corpo)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEDIA_OWNER_MISMATCH"));
	}
}
