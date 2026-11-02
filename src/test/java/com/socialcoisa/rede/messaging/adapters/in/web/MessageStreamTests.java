package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
