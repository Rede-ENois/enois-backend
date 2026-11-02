package com.socialcoisa.rede.push.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.notifications.application.NotificationCreatedEvent;
import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Garante que toda notificacao criada dentro do app tambem vira push no celular: a criacao roda
 * dentro de uma transacao e o envio precisa acontecer depois do commit, sem quebrar o fluxo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushNotificationListenerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NotificationRepository notifications;

	@MockitoBean
	private PushSender pushSender;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void connectionRequestPushesToTheReceiver() throws Exception {
		var requester = auth.authenticate("push_requester");
		var receiver = auth.authenticate("push_receiver");

		mockMvc.perform(post("/network/connections/" + receiver.userId())
				.header("X-Session-Token", requester.token())
				.header("Idempotency-Key", "push-conn-1"))
			.andExpect(status().isAccepted());

		var pushed = org.mockito.ArgumentCaptor.forClass(PushSender.PushMessage.class);
		verify(pushSender, timeout(5000)).sendToUser(eq(receiver.userId()), pushed.capture());
		// The push carries the id of the notification it mirrors, so opening it can mark it read.
		var created = notifications.findByUserId(receiver.userId(), null, null, 20).stream()
			.filter(notification -> requester.userId().equals(notification.actorId()))
			.findFirst()
			.orElseThrow();
		assertThat(pushed.getValue().notificationId()).isEqualTo(created.id());
	}

	@Test
	void doNotDisturbKeepsTheNotificationButSkipsPush() throws Exception {
		var requester = auth.authenticate("push_dnd_requester_" + System.nanoTime());
		var receiver = auth.authenticate("push_dnd_receiver_" + System.nanoTime());
		mockMvc.perform(put("/me/presence")
				.header("X-Session-Token", receiver.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DO_NOT_DISTURB\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/network/connections/" + receiver.userId())
				.header("X-Session-Token", requester.token())
				.header("Idempotency-Key", "push-dnd-conn-" + System.nanoTime()))
			.andExpect(status().isAccepted());
		// O envio roda num executor: espera a janela antes de afirmar que nao houve push.
		verify(pushSender, after(1500).never()).sendToUser(eq(receiver.userId()), any(PushSender.PushMessage.class));
		assertThat(notifications.findByUserId(receiver.userId(), null, null, 20))
			.anyMatch(notification -> requester.userId().equals(notification.actorId()));
	}

	@Test
	void doNotDisturbStillPushesSecurityCategories() throws Exception {
		var receiver = auth.authenticate("push_dnd_always_" + System.nanoTime());
		mockMvc.perform(put("/me/presence")
				.header("X-Session-Token", receiver.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DO_NOT_DISTURB\"}"))
			.andExpect(status().isOk());

		transactions.executeWithoutResult(status -> events.publishEvent(
			new NotificationCreatedEvent("notification_dnd_always", receiver.userId(), "SANCAO", "Sancao", "Corpo", "/notificacoes")));

		verify(pushSender, timeout(5000)).sendToUser(eq(receiver.userId()), any(PushSender.PushMessage.class));
	}
}
