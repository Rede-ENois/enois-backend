package com.socialcoisa.rede.feed.adapters.in.web;

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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Selos de post: a marcacao no topo, ao lado da reacao.
 *
 * O que este teste tranca: VARIOS selos por pessoa no mesmo post (cada um alterna sozinho), a
 * contagem por selo na leitura em lote, a leitura publica (sem sessao) mostrando os selos sem os
 * "meus", e a lista de quem marcou (com quem deu o que) — a que a tela abre ao clicar no selo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostSealTests {

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
	void manySealsPerPersonAndCountsInTheBatchRead() throws Exception {
		var author = auth.authenticate("seal_author1");
		var first = auth.authenticate("seal_first1");
		var second = auth.authenticate("seal_second1");

		String postId = publish(author, "Post que vai receber selos");

		// Duas pessoas marcam o mesmo selo.
		seal(first, postId, "IMPORTANTE").andExpect(status().isOk());
		seal(second, postId, "IMPORTANTE").andExpect(status().isOk());
		// E a primeira acrescenta OUTRO: a mesma pessoa pode ter mais de um selo.
		seal(first, postId, "DICA").andExpect(status().isOk());

		MvcResult batch = mockMvc.perform(get("/feed/posts/seals").param("ids", postId))
			.andExpect(status().isOk())
			.andReturn();
		var node = objectMapper.readTree(batch.getResponse().getContentAsString()).get(postId);
		assertThat(node.get("seals").valueStream().map(seal -> seal.get("seal").asText()).toList())
			.containsExactly("IMPORTANTE", "DICA");
		assertThat(node.get("seals").valueStream().mapToInt(seal -> seal.get("count").asInt()).sum()).isEqualTo(3);
		assertThat(node.get("mySeals").isArray()).isTrue();
		assertThat(node.get("mySeals").valueStream().count()).isZero(); // leitura sem sessao

		// Com sessao, os "meus selos" aparecem todos — e clicar de novo em um tira so ele.
		MvcResult mine = mockMvc.perform(get("/feed/posts/seals").param("ids", postId)
				.header("X-Session-Token", first.token()))
			.andExpect(status().isOk())
			.andReturn();
		var mySeals = objectMapper.readTree(mine.getResponse().getContentAsString()).get(postId).get("mySeals");
		assertThat(mySeals.valueStream().map(seal -> seal.asText()).toList())
			.containsExactlyInAnyOrder("IMPORTANTE", "DICA");

		seal(first, postId, "DICA").andExpect(status().isOk());
		MvcResult after = mockMvc.perform(get("/feed/posts/seals").param("ids", postId)
				.header("X-Session-Token", first.token()))
			.andExpect(status().isOk())
			.andReturn();
		var afterNode = objectMapper.readTree(after.getResponse().getContentAsString()).get(postId);
		assertThat(afterNode.get("seals").valueStream().map(seal -> seal.get("seal").asText()).toList())
			.containsExactly("IMPORTANTE");
		assertThat(afterNode.get("seals").valueStream().mapToInt(seal -> seal.get("count").asInt()).sum()).isEqualTo(2);
		assertThat(afterNode.get("mySeals").valueStream().map(seal -> seal.asText()).toList())
			.containsExactly("IMPORTANTE");

		// O DELETE tira TODOS os selos de quem pediu (e e idempotente).
		mockMvc.perform(delete("/feed/posts/" + postId + "/seals").header("X-Session-Token", second.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/posts/seals").param("ids", postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$." + postId + ".seals[0].seal").value("IMPORTANTE"))
			.andExpect(jsonPath("$." + postId + ".seals[0].count").value(1));
	}

	/** Quem marcou: a lista que a tela abre ao clicar no selo, com o selo de cada pessoa. */
	@Test
	void listsWhoSealedWithTheirSeal() throws Exception {
		var author = auth.authenticate("seal_author3");
		var first = auth.authenticate("seal_first3");
		var second = auth.authenticate("seal_second3");

		String postId = publish(author, "Post para a lista de quem marcou");

		seal(first, postId, "DICA").andExpect(status().isOk());
		seal(first, postId, "INSPIRADOR").andExpect(status().isOk());
		seal(second, postId, "DICA").andExpect(status().isOk());

		MvcResult result = mockMvc.perform(get("/feed/posts/" + postId + "/seals/people"))
			.andExpect(status().isOk())
			.andReturn();
		var node = objectMapper.readTree(result.getResponse().getContentAsString());

		// Contagem do topo: DICA 2, INSPIRADOR 1 (do maior para o menor).
		assertThat(node.get("seals").valueStream().map(seal -> seal.get("seal").asText()).toList())
			.containsExactly("DICA", "INSPIRADOR");
		assertThat(node.get("seals").get(0).get("count").asInt()).isEqualTo(2);
		// E as pessoas, cada uma com o seu selo (a primeira deu dois, entao aparece duas vezes).
		assertThat(node.get("people").valueStream().map(person -> person.get("userId").asText()).toList())
			.containsExactly(first.userId(), first.userId(), second.userId());
		assertThat(node.get("people").valueStream().map(person -> person.get("seal").asText()).toList())
			.containsExactly("DICA", "INSPIRADOR", "DICA");
	}

	@Test
	void rejectsAnUnknownSeal() throws Exception {
		var author = auth.authenticate("seal_author2");
		String postId = publish(author, "Post com selo invalido");

		mockMvc.perform(post("/feed/posts/" + postId + "/seals")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seal\":\"NAO_EXISTE\"}"))
			.andExpect(status().isBadRequest());
	}

	private org.springframework.test.web.servlet.ResultActions seal(TestAuthHelper.AuthSession user, String postId,
			String seal) throws Exception {
		return mockMvc.perform(post("/feed/posts/" + postId + "/seals")
			.header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("seal", seal))));
	}

	private String publish(TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "seal-post-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content, "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
