package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.push.application.port.out.PushSender.PushMessage;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notices of new messages: nobody is notified about the conversation they have open on screen, the
 * messages of one conversation share one unread notice (each push tagged with the conversation, so
 * the device replaces the previous one), and reading the conversation reads its notices.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageNotificationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private PushSender pushSender;

	/** Push is delivered on a thread pool; inline here so the verification sees every delivery. */
	@MockitoBean(name = "pushExecutor")
	private ExecutorService pushExecutor;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(pushExecutor).execute(any());
	}

	@Test
	void theRecipientWithTheConversationOnScreenIsNotNotified() throws Exception {
		var sender = auth.authenticate("msgnotif_open_sender");
		var reader = auth.authenticate("msgnotif_open_reader");
		String conversationId = direct(sender, reader.userId(), "msgnotif-open-direct");

		mockMvc.perform(post("/conversations/" + conversationId + "/viewing")
				.header("X-Session-Token", reader.token()))
			.andExpect(status().isNoContent());
		send(sender, conversationId, "chegou com a conversa aberta", "msgnotif-open-1");

		assertThat(messageNotices(reader)).isEmpty();
		verify(pushSender, never()).sendToUser(eq(reader.userId()), any());

		// Left the conversation: the next message notifies again.
		mockMvc.perform(delete("/conversations/" + conversationId + "/viewing")
				.header("X-Session-Token", reader.token()))
			.andExpect(status().isNoContent());
		send(sender, conversationId, "chegou com a conversa fechada", "msgnotif-open-2");

		assertThat(messageNotices(reader)).hasSize(1);
		verify(pushSender, times(1)).sendToUser(eq(reader.userId()), any());
	}

	@Test
	void theMessagesOfOneConversationShareOneNoticeAndEveryPushCarriesTheConversationTag() throws Exception {
		var sender = auth.authenticate("msgnotif_group_sender");
		var reader = auth.authenticate("msgnotif_group_reader");
		String conversationId = direct(sender, reader.userId(), "msgnotif-group-direct");

		send(sender, conversationId, "primeira", "msgnotif-group-1");
		send(sender, conversationId, "segunda", "msgnotif-group-2");
		send(sender, conversationId, "terceira", "msgnotif-group-3");

		List<JsonNode> notices = messageNotices(reader);
		assertThat(notices).hasSize(1);
		assertThat(notices.getFirst().get("read").asBoolean()).isFalse();
		assertThat(notices.getFirst().get("body").asText()).isEqualTo("3 novas mensagens");

		ArgumentCaptor<PushMessage> pushes = ArgumentCaptor.forClass(PushMessage.class);
		verify(pushSender, times(3)).sendToUser(eq(reader.userId()), pushes.capture());
		assertThat(pushes.getAllValues()).extracting(PushMessage::tag)
			.containsOnly("conversation:" + conversationId);
		assertThat(pushes.getAllValues()).extracting(PushMessage::body)
			.containsExactly("primeira", "2 novas mensagens", "3 novas mensagens");
	}

	@Test
	void readingTheConversationReadsItsNotices() throws Exception {
		var sender = auth.authenticate("msgnotif_read_sender");
		var reader = auth.authenticate("msgnotif_read_reader");
		String conversationId = direct(sender, reader.userId(), "msgnotif-read-direct");
		send(sender, conversationId, "antes de ler", "msgnotif-read-1");
		assertThat(messageNotices(reader)).extracting(n -> n.get("read").asBoolean()).containsExactly(false);

		mockMvc.perform(post("/conversations/" + conversationId + "/read")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "msgnotif-read-mark"))
			.andExpect(status().isOk());

		assertThat(messageNotices(reader)).extracting(n -> n.get("read").asBoolean()).containsExactly(true);

		// Read once, the next message starts a new notice instead of joining the read one.
		send(sender, conversationId, "depois de ler", "msgnotif-read-2");
		assertThat(messageNotices(reader)).extracting(n -> n.get("body").asText())
			.containsExactly("depois de ler", "antes de ler");
	}

	@Test
	void onlyParticipantsCanMarkAConversationAsOnScreen() throws Exception {
		var sender = auth.authenticate("msgnotif_outsider_sender");
		var reader = auth.authenticate("msgnotif_outsider_reader");
		var outsider = auth.authenticate("msgnotif_outsider");
		String conversationId = direct(sender, reader.userId(), "msgnotif-outsider-direct");

		mockMvc.perform(post("/conversations/" + conversationId + "/viewing")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
	}

	private String direct(TestAuthHelper.AuthSession from, String toUserId, String key) throws Exception {
		var result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", from.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", toUserId))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void send(TestAuthHelper.AuthSession from, String conversationId, String text, String key)
			throws Exception {
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", from.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text))))
			.andExpect(status().is2xxSuccessful());
	}

	/** The person's message notices, newest first. */
	private List<JsonNode> messageNotices(TestAuthHelper.AuthSession who) throws Exception {
		var result = mockMvc.perform(get("/notifications").header("X-Session-Token", who.token()))
			.andExpect(status().isOk())
			.andReturn();
		List<JsonNode> notices = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString()).get("items")) {
			if ("NEW_MESSAGE".equals(item.get("kind").asText())) {
				notices.add(item);
			}
		}
		return notices;
	}
}
