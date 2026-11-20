package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * A edicao do comentario respeita o mesmo teto e a mesma defesa de texto da criacao (issue #1103).
 *
 * O caso de 801 caracteres e o de controle: antes do conserto a edicao aceitava ate 1024 e gravava
 * o texto cru, entao este teste falhava nos dois lados (status 200 em vez de recusa, e controle de
 * direcao sobrevivendo na resposta).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EditCommentLimitTests {

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
	void criacaoEedicaoRecusamTextoAcimaDe800() throws Exception {
		var author = auth.authenticate("ecl_author");
		String postId = publish(author);

		// Texto variado: corrida de 13+ do mesmo caractere a defesa colapsa antes do teto.
		String acima = acimaDoTeto();
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ecl-c-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", acima, "mentionedUserIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_TOO_LONG"));

		String commentId = createComment(author, postId, "texto curto do comentario");

		mockMvc.perform(patch("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ecl-p-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", acima))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMENT_TOO_LONG"));

		// Recusar nao pode ter gravado nada: o comentario continua com o texto original.
		assertThat(storedContent(postId, commentId)).isEqualTo("texto curto do comentario");
	}

	/**
	 * O teto e inclusivo: 800 entra inteiro. O texto do fixture nao pode terminar em espaco, porque a
	 * aparada das bordas do normalizeCommentText corta o espaco final e o comentario fica com 799.
	 */
	@Test
	void edicaoAceita800() throws Exception {
		var author = auth.authenticate("ecl_author800");
		String postId = publish(author);
		String commentId = createComment(author, postId, "limite");

		String noLimite = textoVario(800);
		mockMvc.perform(patch("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ecl-800-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", noLimite))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value(noLimite));
	}

	@Test
	void edicaoPassaPelaDefesaDeTexto() throws Exception {
		var author = auth.authenticate("ecl_author_bidi");
		String postId = publish(author);
		String commentId = createComment(author, postId, "antes");

		// Controle de direcao no meio do texto: a criacao ja tira, a edicao tem de tirar igual.
		String comControle = "oi \u202E tudo bem";
		mockMvc.perform(patch("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ecl-bidi-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", comControle))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("oi  tudo bem"));
	}

	/** 801 caracteres de texto legitimo: passa do teto e a defesa nao encurta. */
	private static String acimaDoTeto() {
		return textoVario(801);
	}

	private static String textoVario(int tamanho) {
		String base = "Texto de comentario sem nada suspeito, so letras e espacos. ";
		String texto = base.repeat(tamanho / base.length() + 1).substring(0, tamanho);
		return texto.charAt(tamanho - 1) == ' ' ? texto.substring(0, tamanho - 1) + "x" : texto;
	}

	private String createComment(TestAuthHelper.AuthSession session, String postId, String content) throws Exception {
		MvcResult created = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ecl-new-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", content, "mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String storedContent(String postId, String commentId) throws Exception {
		MvcResult list = mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk())
			.andReturn();
		var items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		for (var item : items) {
			if (commentId.equals(item.path("id").asString())) {
				return item.path("content").asString();
			}
		}
		throw new AssertionError("comentario " + commentId + " nao apareceu na listagem");
	}

	private String publish(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ecl-pub-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post da issue 1103 " + UUID.randomUUID(),
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
