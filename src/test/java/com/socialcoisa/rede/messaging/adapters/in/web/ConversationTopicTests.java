package com.socialcoisa.rede.messaging.adapters.in.web;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Topicos dentro do grupo de conversa (issue #915): quem cria, quem le, o topico da mensagem e a
 * listagem restrita ao topico.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationTopicTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String idem, List<String> participants)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "GROUP", "title", "Grupo com topicos",
					"participantIds", participants))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String createTopic(TestAuthHelper.AuthSession actor, String conversationId, String name)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", actor.token())
				.header("Idempotency-Key", "topic-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String sendInTopic(TestAuthHelper.AuthSession sender, String conversationId, String topicId,
			String text) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "msg-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text, "mediaIds", List.of(),
					"topicId", topicId))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void theOwnerCreatesTopicsAndEveryMemberReadsThemButOnlyManagersWrite() throws Exception {
		var owner = auth.authenticate("ct_owner");
		var member = auth.authenticate("ct_member");
		var other = auth.authenticate("ct_other");
		String conversationId = createGroup(owner, "ct-1", List.of(member.userId()));

		// Membro comum le a lista, mas nao cria nem renomeia.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "ct-create-0")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Jogatina"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_ADMIN_REQUIRED"));

		String topicId = createTopic(owner, conversationId, "  Jogatina   de sexta  ");
		mockMvc.perform(get("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Jogatina de sexta"))
			.andExpect(jsonPath("$.items[0].status").value("OPEN"));

		// Repetir o nome e 409; nome vazio e 400.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Jogatina de sexta"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOPIC_EXISTS"));
		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct-create-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TOPIC_NAME"));

		// Quem nao participa nao enxerga os topicos.
		mockMvc.perform(get("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));

		mockMvc.perform(patch("/conversations/" + conversationId + "/group/topics/" + topicId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct-rename-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Jogatina de sabado"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Jogatina de sabado"));
	}

	@Test
	void aMessageInATopicShowsUnderThatTopicOnly() throws Exception {
		var owner = auth.authenticate("ct2_owner");
		var member = auth.authenticate("ct2_member");
		String conversationId = createGroup(owner, "ct2-1", List.of(member.userId()));
		String jogos = createTopic(owner, conversationId, "Jogos");
		String recados = createTopic(owner, conversationId, "Recados");

		String inJogos = sendInTopic(member, conversationId, jogos, "bora jogar");
		sendInTopic(member, conversationId, recados, "chegando");
		// Sem topico continua valendo (as mensagens antigas se leem como antes).
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "ct2-plain")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "sem topico", "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.topicId").doesNotExist());

		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.param("topicId", jogos)
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(inJogos))
			.andExpect(jsonPath("$.items[0].topicId").value(jogos));

		// A conversa inteira continua trazendo tudo, cada mensagem com o seu topico.
		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3));
	}

	@Test
	void aTopicOfAnotherGroupCannotOrganiseThisConversation() throws Exception {
		var ownerA = auth.authenticate("ct3_owner_a");
		var memberA = auth.authenticate("ct3_member_a");
		var ownerB = auth.authenticate("ct3_owner_b");
		var memberB = auth.authenticate("ct3_member_b");
		String conversationA = createGroup(ownerA, "ct3-a", List.of(memberA.userId()));
		String conversationB = createGroup(ownerB, "ct3-b", List.of(memberB.userId()));
		String foreignTopic = createTopic(ownerB, conversationB, "Do outro grupo");

		mockMvc.perform(post("/conversations/" + conversationA + "/messages")
				.header("X-Session-Token", ownerA.token())
				.header("Idempotency-Key", "ct3-msg")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi", "mediaIds", List.of(),
					"topicId", foreignTopic))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TOPIC_NOT_FOUND"));

		// E o filtro pelo topico alheio devolve lista vazia (nada da conversa A esta la).
		mockMvc.perform(get("/conversations/" + conversationA + "/messages")
				.param("topicId", foreignTopic)
				.header("X-Session-Token", ownerA.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void anArchivedTopicKeepsItsHistoryButTakesNoNewMessage() throws Exception {
		var owner = auth.authenticate("ct4_owner");
		var member = auth.authenticate("ct4_member");
		String conversationId = createGroup(owner, "ct4-1", List.of(member.userId()));
		String topicId = createTopic(owner, conversationId, "Encerrado");
		sendInTopic(owner, conversationId, topicId, "ultima do topico");

		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics/" + topicId + "/archive")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct4-archive"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct4-msg")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "tarde demais", "mediaIds", List.of(),
					"topicId", topicId))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOPIC_ARCHIVED"));

		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.param("topicId", topicId)
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void theReorderNeedsEveryTopicExactlyOnce() throws Exception {
		var owner = auth.authenticate("ct5_owner");
		var member = auth.authenticate("ct5_member");
		String conversationId = createGroup(owner, "ct5-1", List.of(member.userId()));
		String first = createTopic(owner, conversationId, "Primeiro");
		String second = createTopic(owner, conversationId, "Segundo");
		String third = createTopic(owner, conversationId, "Terceiro");

		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics/reorder")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct5-order-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicIds", List.of(third, first)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ORDER"));

		mockMvc.perform(post("/conversations/" + conversationId + "/group/topics/reorder")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "ct5-order")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicIds", List.of(third, first, second)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(third))
			.andExpect(jsonPath("$.items[1].id").value(first))
			.andExpect(jsonPath("$.items[2].id").value(second));

		var listed = mockMvc.perform(get("/conversations/" + conversationId + "/group/topics")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(listed).get("items").get(0).get("name").asText()).isEqualTo("Terceiro");
	}

	@Test
	void topicsAreOnlyForGroups() throws Exception {
		var a = auth.authenticate("ct6_a");
		var b = auth.authenticate("ct6_b");
		MvcResult dm = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "ct6-dm")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String directId = objectMapper.readTree(dm.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/conversations/" + directId + "/group/topics")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CONVERSATION_KIND"));
	}

}
