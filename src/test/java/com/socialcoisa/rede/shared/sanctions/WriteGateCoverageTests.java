package com.socialcoisa.rede.shared.sanctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.socialcoisa.rede.moderation.application.SanctionService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Cobertura dos portões de escrita: toda conta que escreve passa pelos mesmos portões.
 *
 * <p>Em 2026-10-21 a sanção de moderação (a restrição, decisão humana de "não publica nem
 * comenta") era conferida só no post e no comentário do feed. A mesma conta restrita continuava
 * publicando em grupo, comentando em grupo e mandando mensagem privada — e a mensagem privada é
 * justamente o canal onde a moderação não enxerga. Pior: as escritas de grupo nem passavam pelo
 * portão de idade (ECA Digital), então uma conta de menor sem autorização do responsável
 * publicava normalmente em grupo.
 *
 * <p>Este teste fecha as duas brechas e prova, no mesmo lugar, que a conta sem nada contra ela
 * continua escrevendo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteGateCoverageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SanctionService sanctions;

	@Autowired
	private com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void contaRestritaNaoPublicaEmGrupo() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-restrita-grupo");
		String groupId = createGroup(author, "Grupo do portao (restrita)");
		String topicId = createTopic(author, groupId);

		sanctions.restrictUser(author.userId(), "test-portao-grupo", "Teste do portao de escrita.");

		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-grupo-restrita")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", "publicacao de conta restrita", "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
	}

	/**
	 * A punição que o usuário não consegue ver vira ticket de suporte: a recusa tem que dizer o
	 * que a conta é (restrita/suspensa/banida), o motivo e, na suspensão, até quando revisa.
	 */
	@Test
	void recusaDeContaPunidaDizMotivoEPrazo() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-restrita-motivo");
		String groupId = createGroup(author, "Grupo do portao (motivo)");
		String topicId = createTopic(author, groupId);

		sanctions.restrictUser(author.userId(), "test-portao-motivo", "Spam repetido na rede.");

		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-grupo-motivo")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", "publicacao de conta restrita", "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
			.andExpect(jsonPath("$.message").value(
				org.hamcrest.Matchers.containsString("restrita por moderação")))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Motivo: Spam repetido na rede.")));
	}

	@Test
	void contaRestritaNaoComentaEmGrupo() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-restrita-comentario");
		String groupId = createGroup(author, "Grupo do portao (comentario)");
		String topicId = createTopic(author, groupId);
		String postId = objectMapper.readTree(publish(author, groupId, topicId, "publicacao antes da restricao")
			.getResponse().getContentAsString()).get("id").asText();

		sanctions.restrictUser(author.userId(), "test-portao-comentario", "Teste do portao de escrita.");

		mockMvc.perform(post("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-comentario-restrita")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "comentario de conta restrita"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
	}

	@Test
	void contaRestritaNaoMandaMensagemPrivada() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-restrita-mensagem");
		TestAuthHelper.AuthSession outra = auth.authenticate("portao-outra-mensagem");
		String conversationId = directConversation(author, outra.userId());

		sanctions.restrictUser(author.userId(), "test-portao-mensagem", "Teste do portao de escrita.");

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-mensagem-restrita")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "mensagem de conta restrita"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
	}

	@Test
	void menorSemAutorizacaoNaoPublicaEmGrupo() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-menor-grupo");
		String groupId = createGroup(author, "Grupo do portao (menor)");
		String topicId = createTopic(author, groupId);

		// A conta vira menor de 18 na base; sem a autorização do responsável, o portão de idade
		// (ECA Digital) barra a escrita — como já barra o post e o comentário do feed.
		jdbc.update("UPDATE users SET birth_date = ? WHERE id = ?", LocalDate.now().minusYears(15).toString(),
			author.userId());

		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-grupo-menor")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", "publicacao de menor sem autorizacao", "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PARENTAL_CONSENT_REQUIRED"));
	}

	@Test
	void contaSemNadaContraElaContinuaEscrevendo() throws Exception {
		TestAuthHelper.AuthSession author = auth.authenticate("portao-livre");
		String groupId = createGroup(author, "Grupo do portao (livre)");
		String topicId = createTopic(author, groupId);

		MvcResult post = publish(author, groupId, topicId, "publicacao de quem nao tem nada contra ela");
		assertThat(post.getResponse().getStatus()).isEqualTo(201);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "portao-cria-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String createTopic(TestAuthHelper.AuthSession admin, String groupId) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "portao-topico-" + groupId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Geral", "description", "assunto"))))
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private MvcResult publish(TestAuthHelper.AuthSession author, String groupId, String topicId, String content)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "portao-publica-" + groupId + "-" + content.length())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", content, "mediaIds", List.of()))))
			.andReturn();
		return result;
	}

	private String directConversation(TestAuthHelper.AuthSession sender, String otherUserId) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "portao-conversa-" + otherUserId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", otherUserId))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
