package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Banido vira "Usuário" (issue #1021): o conteúdo de quem foi banido continua no fio — sumir
 * deixava posts e comentários órfãos, "desconexos" — mas sem nome, @, avatar nem badges. É a
 * anonimização que o Instagram aplica e que o perfil já aplicava; aqui o mesmo tratamento chega
 * ao feed e aos comentários, e vale também para quem foi banido antes da mudança (a leitura
 * decide, não a escrita).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class BannedAuthorAnonymizedTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void commentOfBannedAuthorShowsUsuarioWithoutIdentity() throws Exception {
		var author = auth.authenticate("bananon_author_" + System.nanoTime());
		var commenter = auth.authenticate("bananon_commenter_" + System.nanoTime());
		String postId = publish(author, "Post do fio que recebe comentario de banido.");
		String commentId = comment(commenter, postId, "Comentario de quem vai ser banido.", 1);

		// Antes do banimento: o comentário tem o nome de verdade, não o genérico.
		String before = mockMvc.perform(get("/feed/posts/{id}/comments", postId))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(before)
			.contains(commentId)
			.contains("\"authorName\":\"bananon_commenter_");

		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'Conta de teste banida.', 'ACTIVE', now())
			""", "san_bananon_" + System.nanoTime(), commenter.userId());

		// Depois: o comentário continua, identidade não. Username nulo — o front não vira link
		// para o perfil que nem abre mais.
		String after = mockMvc.perform(get("/feed/posts/{id}/comments", postId))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(after)
			.contains("\"authorName\":\"Usuário\"")
			.doesNotContain("\"authorUsername\":\"");
		// O nome visível não é o que estava antes do banimento.
		org.assertj.core.api.Assertions.assertThat(after).doesNotContain("bananon_commenter_");
	}

	@Test
	void feedPostOfBannedAuthorShowsUsuarioWithoutIdentity() throws Exception {
		var author = auth.authenticate("bananon_poster_" + System.nanoTime());
		String postId = publish(author, "Post de quem vai ser banido depois de publicar.");

		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'Conta de teste banida.', 'ACTIVE', now())
			""", "san_banonpost_" + System.nanoTime(), author.userId());

		// O #715 (sessão de consulta) não autentica mais a sessão de quem foi banido: a leitura do
		// post único é pública de propósito (é o link compartilhado), então o fio continua legível.
		MvcResult page = mockMvc.perform(get("/feed/posts/{id}", postId))
			.andExpect(status().isOk())
			.andReturn();
		String body = page.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(body)
			.contains(postId)
			.contains("\"authorName\":\"Usuário\"")
			.doesNotContain("\"authorName\":\"" + author.userId() + "\"")
			// O nome do post não pode ser nem o id da conta nem nenhum nome real: o enrich
			// colapsa tudo que é banido para o mesmo "Usuário".
			.contains("\"authorName\":\"Usuário\"");
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "bananon-pub-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", java.util.List.of(),
					"mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content, int n) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/{id}/comments", postId)
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "bananon-c" + n)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
