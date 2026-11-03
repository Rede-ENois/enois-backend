package com.socialcoisa.rede.feed.adapters.out.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O que a triagem por IA recebe como CONTEUDO do alvo denunciado.
 *
 * O buraco que este teste tranca: a triagem mandava o ID do alvo ("post_1e4c9acf...") no lugar do
 * conteudo — o modelo nao tinha contexto nenhum para julgar assedio, discriminacao ou spam, e o
 * veredito caia em revisao humana (ou decidia so pela descricao do denunciante).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportedContentAdapterTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportedContentReader reader;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void readsPostText() throws Exception {
		var author = auth.authenticate("user_reported_content1");
		String postId = publishPost(author, Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT", "content", "Compre seguidores barato no meu site", "tags", List.of(),
			"mediaIds", List.of()), "content-post-1");

		assertThat(reader.contentOf("POST", postId)).hasValueSatisfying(text ->
			assertThat(text).contains("Compre seguidores barato no meu site"));
	}

	@Test
	void readsPageHtmlToo() throws Exception {
		var author = auth.authenticate("user_reported_content2");
		String postId = publishPost(author, Map.of("topic", "TECNOLOGIA",
			"type", "PAGE", "content", "Titulo da pagina", "pageHtml", "<h1>corpo da pagina denunciada</h1>",
			"tags", List.of(), "mediaIds", List.of()), "content-post-2");

		assertThat(reader.contentOf("POST", postId)).hasValueSatisfying(text ->
			assertThat(text).contains("corpo da pagina denunciada").contains("Titulo da pagina"));
	}

	@Test
	void readsCommentText() throws Exception {
		var author = auth.authenticate("user_reported_content3");
		String postId = publishPost(author, Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT", "content", "post com comentario denunciado", "tags", List.of(),
			"mediaIds", List.of()), "content-post-3");

		MvcResult comment = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "content-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "seu idiota, some daqui", "mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String commentId = objectMapper.readTree(comment.getResponse().getContentAsString()).get("id").asText();

		assertThat(reader.contentOf("COMMENT", commentId)).hasValueSatisfying(text ->
			assertThat(text).contains("seu idiota"));
	}

	@Test
	void readsWhatAProfileShows() throws Exception {
		var person = auth.authenticate("perfil_denunciado_spam");

		assertThat(reader.contentOf("USER", person.userId())).hasValueSatisfying(text ->
			assertThat(text).contains("perfil_denunciado_spam"));
	}

	@Test
	void emptyForTargetsWithoutText() {
		assertThat(reader.contentOf("JOB", "job_inexistente")).isEmpty();
		assertThat(reader.contentOf("POST", "post_inexistente")).isEmpty();
		assertThat(reader.contentOf(null, "qualquer")).isEmpty();
	}

	private String publishPost(TestAuthHelper.AuthSession author, Map<String, Object> body, String idem)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
