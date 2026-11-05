package com.socialcoisa.rede.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.blog.application.BlogNewsletterService;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.notifications.application.WeeklyDigestService.Outcome;
import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Weekly digest: sent once per ISO week and only with real content, never to someone who opted
 * out, and the one-click opt-out link works (and rejects tampered or foreign tokens).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyDigestTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private WeeklyDigestService digest;

	@Autowired
	private WeeklyDigestRepository digests;

	@Autowired
	private NotificationPreferenceService preferences;

	@Autowired
	private BlogNewsletterService blogNewsletter;

	@Autowired
	private JdbcTemplate jdbc;

	/** No SMTP in the suite: the stub accepts every send and records the call. */
	@MockitoBean
	private EmailService email;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		when(email.sendHtml(anyString(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(true);
	}

	@Test
	void sendsOncePerWeekWithRealContent() throws Exception {
		var reader = auth.authenticate("digest_send_reader");
		var author = auth.authenticate("digest_send_author");
		String community = community("digest-send", reader.userId());
		communityPost(community, author.userId(), "Como vocês organizam o estudo de algoritmos?", 4);
		communityPost(community, author.userId(), "Compartilhando um guia de testes de contrato", 1);
		job(author.userId(), "Pessoa engenheira Kotlin digest", "Kotlin", reader.userId());
		event(reader.userId(), "Encontro de Kotlin digest");
		group("digest-send", reader.userId(), author.userId());

		assertThat(digest.sendTo(reader.userId())).isEqualTo(Outcome.SENT);
		assertThat(digest.sendTo(reader.userId())).isEqualTo(Outcome.ALREADY_SENT);

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> unsubscribe = ArgumentCaptor.forClass(String.class);
		verify(email, times(1)).sendHtml(eq("digest_send_reader@test.com"), eq("Sua semana no Énóis"),
			body.capture(), eq("Énóis"), any(), unsubscribe.capture(), eq("WEEKLY_DIGEST"));
		assertThat(body.getValue())
			.contains("Como vocês organizam o estudo de algoritmos?")
			.contains("Pessoa engenheira Kotlin digest")
			.contains("Encontro de Kotlin digest")
			.contains("Conversas novas em Grupo digest-send")
			.contains("Não quero mais receber")
			.contains(unsubscribe.getValue().replace("&", "&amp;"));
		assertThat(unsubscribe.getValue()).contains("/api/notifications/digest/unsubscribe?token=");
		// Delivered this week: the scheduler's page no longer offers this person.
		assertThat(digests.eligibleUserIds(digest.weekKey(), "", 1_000_000)).doesNotContain(reader.userId());
	}

	@Test
	void skipsSilentlyWhenThereIsNotEnoughContent() throws Exception {
		var reader = auth.authenticate("digest_empty_reader");
		var author = auth.authenticate("digest_empty_author");
		String community = community("digest-empty", reader.userId());
		// The person's own posts never count, and one item alone is not a digest.
		communityPost(community, reader.userId(), "Meu proprio post", 9);
		communityPost(community, reader.userId(), "Outro post meu", 9);
		communityPost(community, author.userId(), "Unico post de outra pessoa", 1);

		assertThat(digest.sendTo(reader.userId())).isEqualTo(Outcome.EMPTY);

		verify(email, never()).sendHtml(eq("digest_empty_reader@test.com"), anyString(), anyString(), any(), any(),
			any(), any());
		// Nothing recorded: next week it tries again.
		assertThat(jdbc.queryForObject("select count(*) from digest_deliveries where user_id = ?", Integer.class,
			reader.userId())).isZero();
		assertThat(digests.eligibleUserIds(digest.weekKey(), "", 1_000_000)).contains(reader.userId());
	}

	@Test
	void mutedAndBlockedPeopleStayOutOfTheDigest() throws Exception {
		var reader = auth.authenticate("digest_hidden_reader");
		var muted = auth.authenticate("digest_hidden_muted");
		var blocker = auth.authenticate("digest_hidden_blocker");
		String community = community("digest-hidden", reader.userId());
		communityPost(community, muted.userId(), "Post de quem foi silenciado", 5);
		communityPost(community, muted.userId(), "Outro post silenciado", 5);
		communityPost(community, blocker.userId(), "Post de quem bloqueou", 5);
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into user_mutes (muter_id, muted_id, created_at) values (?, ?, ?)", reader.userId(),
			muted.userId(), now);
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, ?)", blocker.userId(),
			reader.userId(), now);

		assertThat(digest.sendTo(reader.userId())).isEqualTo(Outcome.EMPTY);
		verify(email, never()).sendHtml(eq("digest_hidden_reader@test.com"), anyString(), anyString(), any(), any(),
			any(), any());
	}

	@Test
	void neverSendsToWhoOptedOut() throws Exception {
		var reader = auth.authenticate("digest_optout_reader");
		var author = auth.authenticate("digest_optout_author");
		String community = community("digest-optout", reader.userId());
		communityPost(community, author.userId(), "Post um", 1);
		communityPost(community, author.userId(), "Post dois", 1);
		preferences.update(reader.userId(), false, null, null, null, null, null);

		assertThat(digest.sendTo(reader.userId())).isEqualTo(Outcome.NOT_ELIGIBLE);
		assertThat(digests.eligibleUserIds(digest.weekKey(), "", 1_000_000)).doesNotContain(reader.userId());
		verify(email, never()).sendHtml(eq("digest_optout_reader@test.com"), anyString(), anyString(), any(), any(),
			any(), any());
	}

	@Test
	void unsubscribeLinkTurnsTheDigestOffAndRejectsForgedTokens() throws Exception {
		var reader = auth.authenticate("digest_unsub_reader");
		String token = digest.unsubscribeToken(reader.userId());

		// Tampered token: the cipher is authenticated, so a changed byte is rejected.
		char last = token.charAt(token.length() - 1);
		String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
		mockMvc.perform(get("/notifications/digest/unsubscribe").param("token", tampered))
			.andExpect(status().isBadRequest());
		// A valid token of another purpose (blog newsletter) does not touch the digest.
		mockMvc.perform(get("/notifications/digest/unsubscribe")
				.param("token", blogNewsletter.unsubscribeToken("blog_x", reader.userId())))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/notifications/digest/unsubscribe"))
			.andExpect(status().isBadRequest());
		assertThat(preferences.of(reader.userId()).weeklyDigest()).isTrue();

		mockMvc.perform(get("/notifications/digest/unsubscribe").param("token", token))
			.andExpect(status().isOk());
		assertThat(preferences.of(reader.userId()).weeklyDigest()).isFalse();
		// Idempotent: the second click is still a confirmation, not an error.
		mockMvc.perform(get("/notifications/digest/unsubscribe").param("token", token))
			.andExpect(status().isOk());
	}

	@Test
	void oneClickPostFromTheMailClientTurnsTheDigestOff() throws Exception {
		var reader = auth.authenticate("digest_oneclick_reader");
		String token = digest.unsubscribeToken(reader.userId());
		char last = token.charAt(token.length() - 1);
		String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

		// RFC 8058: no session, no Origin, form body "List-Unsubscribe=One-Click", token in the URL.
		mockMvc.perform(post("/notifications/digest/unsubscribe").param("token", tampered)
				.contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
				.content("List-Unsubscribe=One-Click"))
			.andExpect(status().isBadRequest());
		assertThat(preferences.of(reader.userId()).weeklyDigest()).isTrue();

		mockMvc.perform(post("/notifications/digest/unsubscribe").queryParam("token", token)
				.contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
				.content("List-Unsubscribe=One-Click"))
			.andExpect(status().isOk());
		assertThat(preferences.of(reader.userId()).weeklyDigest()).isFalse();
	}

	private String community(String prefix, String memberId) {
		String id = prefix + "-" + UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into communities (id, name, description, topic, created_by, created_at, updated_at) "
			+ "values (?, ?, 'desc', 'TECNOLOGIA', ?, ?, ?)", id, "Comunidade " + prefix, memberId, now, now);
		jdbc.update("insert into community_members (community_id, user_id, role, joined_at) values (?, ?, 'MEMBER', ?)",
			id, memberId, now);
		return id;
	}

	private void group(String slug, String memberId, String authorId) {
		String id = "digest-group-" + UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into groups (id, slug, name, owner_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
			id, slug + "-" + UUID.randomUUID().toString().substring(0, 8), "Grupo " + slug, authorId, now, now);
		jdbc.update("insert into group_members (id, group_id, user_id, joined_at) values (?, ?, ?, ?)",
			UUID.randomUUID().toString(), id, memberId, now);
		jdbc.update("insert into group_posts (id, group_id, topic_id, author_id, content, created_at, updated_at) "
			+ "values (?, ?, 'topic', ?, 'Conteudo do grupo', ?, ?)", UUID.randomUUID().toString(), id, authorId, now, now);
	}

	private void communityPost(String communityId, String authorId, String content, int reactions) {
		jdbc.update("insert into posts (id, author_id, type, content, created_at, visibility, reactions, comments, "
			+ "saves, shares, status, community_id) values (?, ?, 'INSIGHT', ?, ?, 'PUBLIC', ?, 0, 0, 0, 'ACTIVE', ?)",
			"digest-post-" + UUID.randomUUID(), authorId, content, Timestamp.from(Instant.now()), reactions, communityId);
	}

	private void job(String posterId, String title, String skill, String candidateId) {
		String id = "digest-job-" + UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into jobs (id, title, company, type, level, posted_by, posted_at, updated_at, status) "
			+ "values (?, ?, 'Acme', 'CLT', 'PLENO', ?, ?, ?, 'OPEN')", id, title, posterId, now, now);
		jdbc.update("insert into job_skills (job_id, skill) values (?, ?)", id, skill);
		jdbc.update("insert into user_skills (user_id, skill) values (?, ?)", candidateId, skill.toLowerCase());
	}

	private void event(String attendeeId, String title) {
		String id = "digest-event-" + UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		String date = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString();
		jdbc.update("insert into events (id, title, date, time, format, host, created_by, created_at, updated_at) "
			+ "values (?, ?, ?, '19:00', 'ONLINE', 'Casa', ?, ?, ?)", id, title, date, attendeeId, now, now);
		jdbc.update("insert into event_attendees (event_id, user_id, responded_at) values (?, ?, ?)", id, attendeeId,
			now);
	}
}
