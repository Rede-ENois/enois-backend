package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** The inbox stream tells participants a conversation changed, so the browser stops polling. */
@SpringBootTest
@AutoConfigureMockMvc
class MessageStreamTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MessageStreamHub hub;

	@Test
	void newMessageIsSignalledToTheRecipientStream() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var sender = auth.authenticate("msg_stream_sender");
		var recipient = auth.authenticate("msg_stream_recipient");
		var outsider = auth.authenticate("msg_stream_outsider");
		String conversationId = directConversation(sender.token(), recipient.userId());

		MvcResult recipientStream = openStream(recipient.token());
		MvcResult outsiderStream = openStream(outsider.token());

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "msg-stream-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi"))))
			.andExpect(status().is2xxSuccessful());

		assertThat(recipientStream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(recipientStream.getResponse().getContentAsString())
			.contains("event:conversation")
			.contains(conversationId);
		// The signal only names the conversation; content is fetched through the authorized endpoints.
		assertThat(recipientStream.getResponse().getContentAsString()).doesNotContain("oi\"");
		assertThat(outsiderStream.getResponse().getContentAsString()).doesNotContain(conversationId);
	}

	@Test
	void readReceiptIsSignalledToTheOtherParticipant() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var sender = auth.authenticate("msg_stream_read_a");
		var reader = auth.authenticate("msg_stream_read_b");
		String conversationId = directConversation(sender.token(), reader.userId());
		MvcResult senderStream = openStream(sender.token());

		mockMvc.perform(post("/conversations/" + conversationId + "/read")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "msg-stream-read"))
			.andExpect(status().is2xxSuccessful());

		assertThat(senderStream.getResponse().getContentAsString()).contains(conversationId);
	}

	/**
	 * O sino recarrega sozinho: o aviso criado com a aba aberta chega pelo stream. Antes o stream só
	 * falava de conversa e chamada, então a notificação só aparecia no próximo carregamento.
	 */
	@Test
	void newNotificationIsSignalledToItsOwnersStreamOnly() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("msg_stream_notice_author");
		var reposter = new TestAuthHelper(mockMvc, objectMapper).authenticate("msg_stream_notice_reposter");
		var outsider = new TestAuthHelper(mockMvc, objectMapper).authenticate("msg_stream_notice_outsider");

		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "msg-stream-notice-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", "Post que será republicado para gerar o aviso.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		MvcResult authorStream = openStream(author.token());
		MvcResult outsiderStream = openStream(outsider.token());

		mockMvc.perform(post("/feed/posts/{id}/repost", postId)
				.header("X-Session-Token", reposter.token()))
			.andExpect(status().isCreated());

		String signal = authorStream.getResponse().getContentAsString();
		assertThat(signal).contains("event:notification").contains("notificationId").contains("POST_REPOSTED");
		// O aviso é só de quem recebeu: o stream alheio não fica sabendo.
		assertThat(outsiderStream.getResponse().getContentAsString()).doesNotContain("event:notification");
	}

	@Test
	void streamsPerUserAreCapped() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("msg_stream_cap");
		for (int i = 0; i < MessageStreamHub.MAX_STREAMS_PER_USER + 3; i++) {
			openStream(user.token());
		}
		assertThat(hub.openStreams(user.userId())).isEqualTo(MessageStreamHub.MAX_STREAMS_PER_USER);
	}

	@Test
	void streamRequiresASession() throws Exception {
		mockMvc.perform(get("/conversations/stream").accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isUnauthorized());
	}

	private MvcResult openStream(String token) throws Exception {
		return mockMvc.perform(get("/conversations/stream")
				.header("X-Session-Token", token)
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private String directConversation(String token, String otherUserId) throws Exception {
		MvcResult created = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "msg-stream-direct-" + otherUserId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", otherUserId))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}
}
