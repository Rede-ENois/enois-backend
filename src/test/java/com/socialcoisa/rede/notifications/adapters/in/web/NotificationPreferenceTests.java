package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.notifications.application.NotificationPreferenceService;
import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Notification preferences: defaults, partial update, and the push filter. The in-app
 * notification is always created; only the push obeys the category toggle, and security is
 * pushed even with every toggle off.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPreferenceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NotificationService notifications;

	@Autowired
	private TransactionTemplate transactions;

	@MockitoBean
	private PushSender pushSender;

	/** Push is delivered on a thread pool; inline here so each verify sees a finished delivery. */
	@MockitoBean(name = "pushExecutor")
	private ExecutorService pushExecutor;

	private TestAuthHelper auth;
	@Autowired private JdbcTemplate jdbc;
	private final List<String> projectPreferenceUsers = new ArrayList<>();
	@Autowired private NotificationPreferenceService preferencias;

	@AfterEach
	void cleanProjectPreferences() {
		for (String userId : projectPreferenceUsers) {
			jdbc.update("delete from notification_preferences where user_id = ?", userId);
		}
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(pushExecutor).execute(any());
	}

	@Test
	void defaultsAreAllOnAndPartialUpdateKeepsTheRest() throws Exception {
		var user = auth.authenticate("npref_crud");

		mockMvc.perform(get("/me/notification-preferences"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.weeklyDigest").value(true))
			.andExpect(jsonPath("$.pushSocial").value(true))
			.andExpect(jsonPath("$.pushMessages").value(true))
			.andExpect(jsonPath("$.pushGroups").value(true))
			.andExpect(jsonPath("$.pushJobs").value(true))
			.andExpect(jsonPath("$.pushSystem").value(true));

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushSocial\":false,\"weeklyDigest\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushSocial").value(false))
			.andExpect(jsonPath("$.weeklyDigest").value(false))
			.andExpect(jsonPath("$.pushMessages").value(true))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		// A second partial update touches only its own field.
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushJobs\":false}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushSocial").value(false))
			.andExpect(jsonPath("$.weeklyDigest").value(false))
			.andExpect(jsonPath("$.pushJobs").value(false))
			.andExpect(jsonPath("$.pushGroups").value(true));
	}

	@Test
	void communityProjectsStartDisabledAndKeepTheExplicitChoice() throws Exception {
		var user = auth.authenticate("npref_projects");
		projectPreferenceUsers.add(user.userId());

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.communityProjects").value(false));

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.communityProjects").value(true))
			.andExpect(jsonPath("$.pushSocial").value(true));

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushSocial\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.communityProjects").value(true));

		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.communityProjects").value(true))
			.andExpect(jsonPath("$.pushSocial").value(false));

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":null}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(true));
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(true));
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":false}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(false));
		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", user.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(false));
	}

	@Test
	void atualizacoesConcorrentesPreservamAssinaturaEOutraCategoria() throws Exception {
		var conta = auth.authenticate("npref_concorrente_676");
		projectPreferenceUsers.add(conta.userId());
		try (var executor = Executors.newFixedThreadPool(2)) {
			for (int rodada = 0; rodada < 20; rodada++) {
				if (rodada % 2 == 0) jdbc.update("delete from notification_preferences where user_id = ?", conta.userId());
				else jdbc.update("update notification_preferences set community_projects = false, push_social = true where user_id = ?", conta.userId());
				var inicio = new CountDownLatch(1);
				var projetos = executor.submit(() -> {
					if (!inicio.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Atualização não iniciou no prazo");
					return preferencias.update(conta.userId(), null, null, null, null, null, null, true);
				});
				var social = executor.submit(() -> {
					if (!inicio.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Atualização não iniciou no prazo");
					return preferencias.update(conta.userId(), null, false, null, null, null, null, null);
				});
				inicio.countDown();
				projetos.get(20, TimeUnit.SECONDS);
				social.get(20, TimeUnit.SECONDS);
				var salvo = preferencias.of(conta.userId());
				assertThat(salvo.communityProjects()).as("assinatura da rodada %s", rodada).isTrue();
				assertThat(salvo.pushSocial()).as("categoria da rodada %s", rodada).isFalse();
			}
		}
	}

	@Test
	void existingRowsDefaultOffAndOnlyTheAuthenticatedAccountChanges() throws Exception {
		var existing = auth.authenticate("npref_legacy");
		var other = auth.authenticate("npref_other");
		projectPreferenceUsers.add(existing.userId());
		projectPreferenceUsers.add(other.userId());
		jdbc.update("""
			insert into notification_preferences (user_id, weekly_digest, push_social, updated_at)
			values (?, false, false, current_timestamp)""", existing.userId());
		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", existing.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(false))
			.andExpect(jsonPath("$.weeklyDigest").value(false)).andExpect(jsonPath("$.pushSocial").value(false));
		mockMvc.perform(put("/me/notification-preferences").contentType(MediaType.APPLICATION_JSON)
				.content("{\"communityProjects\":true}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":true}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(true));
		mockMvc.perform(get("/me/notification-preferences").header("X-Session-Token", existing.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.communityProjects").value(false));
	}

	@Test
	void projectPushUsesTheExplicitChoiceIndependentlyOfOtherCategories() throws Exception {
		var user = auth.authenticate("npref_project_push");
		projectPreferenceUsers.add(user.userId());
		notify(user.userId(), "NEW_COMMUNITY_PROJECT");
		verify(pushSender, never()).sendToUser(eq(user.userId()), any());
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"communityProjects\":true,\"pushGroups\":false,\"pushSystem\":false}"))
			.andExpect(status().isOk());
		notify(user.userId(), "NEW_COMMUNITY_PROJECT");
		verify(pushSender).sendToUser(eq(user.userId()), any());
		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"communityProjects\":false}"))
			.andExpect(status().isOk());
		notify(user.userId(), "NEW_COMMUNITY_PROJECT");
		verify(pushSender, times(1)).sendToUser(eq(user.userId()), any());
	}

	@Test
	void pushRespectsTheCategoryAndSecurityIsAlwaysSent() throws Exception {
		var user = auth.authenticate("npref_push");
		String userId = user.userId();

		notify(userId, "NEW_REACTION");
		verify(pushSender).sendToUser(eq(userId), any());

		mockMvc.perform(put("/me/notification-preferences").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pushSocial\":false,\"pushMessages\":false,\"pushGroups\":false,\"pushJobs\":false,\"pushSystem\":false}"))
			.andExpect(status().isOk());

		clearInvocations(pushSender);
		notify(userId, "NEW_REACTION");
		notify(userId, "NEW_MESSAGE");
		notify(userId, "JOB_APPLICATION_STATUS");
		verify(pushSender, never()).sendToUser(eq(userId), any());

		notify(userId, "SEGURANCA");
		verify(pushSender).sendToUser(eq(userId), any());

		// The inbox still has everything: only the push was filtered.
		mockMvc.perform(get("/notifications").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(5));
	}

	/** In its own transaction: the push listener runs after commit. */
	private void notify(String userId, String kind) {
		transactions.executeWithoutResult(status -> notifications.create(userId, kind, "sistema", "Titulo", "Corpo",
			"/feed", "Ver"));
	}
}
