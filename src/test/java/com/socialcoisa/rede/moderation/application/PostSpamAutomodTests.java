package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.ai.ModerationAiService;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.AdminGuard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Automod de spam/flood SEM denúncia: o determinístico levanta a suspeita (flood, repetição,
 * sequência de posts vazios) e a IA decide com o mesmo gate da triagem. Nada é apagado nem
 * aplicado sem um PENALIZAR confirmado — e é isso que os casos abaixo travam.
 */
@ExtendWith(MockitoExtension.class)
@Tag("critico")
class PostSpamAutomodTests {

	private static final String USER = "user_spam_tests";
	private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

	@Mock
	private PostRepository posts;

	@Mock
	private ModerationAiService ai;

	@Mock
	private ModerationRemovePostUseCase removePost;

	@Mock
	private SanctionService sanctions;

	@Mock
	private AuthSessionRepository sessions;

	@Mock
	private AdminGuard adminGuard;

	@Mock
	private ModeratorService moderators;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private PostSpamAutomodService service;

	@BeforeEach
	void setUp() {
		service = new PostSpamAutomodService(posts, ai, removePost, sanctions, sessions, adminGuard, moderators,
			Clock.fixed(NOW, ZoneOffset.UTC), true, 3, 10, 8, 3, 8, 168, 50, 20, 0);
		when(adminGuard.isAdmin(any())).thenReturn(false);
		when(moderators.isModerator(USER)).thenReturn(false);
	}

	@Test
	void floodConfirmedByAiIsRemovedAndRestricted() throws Exception {
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(recentPosts(8, 1));
		giveVerdict("PENALIZAR", 0.95, false);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("PENALIZED");
		assertThat(outcome.removed()).isEqualTo(8);
		assertThat(outcome.sanction()).isEqualTo("RESTRICTION");
		verify(removePost, times(8)).removeAsModerator(eq(PostSpamAutomodService.SYSTEM_MODERATOR), anyString(),
			anyString());
		verify(sanctions).restrictUser(eq(USER), anyString(), anyString());
		verify(sanctions, never()).banAuthor(any(), anyString(), anyString(), any());
	}

	@Test
	void withoutAiConfirmationNothingIsRemoved() throws Exception {
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(recentPosts(8, 1));
		giveVerdict("NENHUMA", 0.2, true);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("NEEDS_REVIEW");
		assertThat(outcome.removed()).isZero();
		verifyNoInteractions(removePost);
		verify(sanctions, never()).restrictUser(anyString(), anyString(), anyString());
	}

	@Test
	void aCoupleOfPostsInTheWindowIsNotASignal() {
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(recentPosts(3, 1));

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("NO_SIGNAL");
		verifyNoInteractions(ai, removePost);
	}

	@Test
	void massiveFloodConfirmedByAiBansTheAccount() throws Exception {
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(recentPosts(20, 1));
		when(sanctions.restrictUser(eq(USER), anyString(), anyString())).thenReturn(null);
		giveVerdict("PENALIZAR", 0.97, false);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("PENALIZED");
		assertThat(outcome.sanction()).isEqualTo("BAN");
		verify(sanctions).banAuthor(any(), eq(USER), eq(PostSpamAutomodService.SYSTEM_MODERATOR), eq(sessions));
	}

	@Test
	void repeatedTextIsASignalEvenWithoutFlood() throws Exception {
		List<Post> repetidos = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			repetidos.add(post("post_rep_" + i, NOW.minusSeconds(120 + i), "  Ganhe dinheiro fácil, clique aqui  "));
		}
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(repetidos);
		giveVerdict("PENALIZAR", 0.9, false);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("PENALIZED");
		assertThat(outcome.removed()).isEqualTo(3);
	}

	@Test
	void aRunOfPracticallyEmptyPostsIsASignal() throws Exception {
		List<Post> vazios = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			vazios.add(post("post_curto_" + i, NOW.minusSeconds(60 + i), i % 2 == 0 ? "." : "oi"));
		}
		when(posts.findActiveByAuthor(eq(new UserId(USER)), any(), anyInt())).thenReturn(vazios);
		giveVerdict("PENALIZAR", 0.92, false);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("PENALIZED");
		assertThat(outcome.removed()).isEqualTo(8);
	}

	@Test
	void staffIsOutOfTheAutomod() {
		when(moderators.isModerator(USER)).thenReturn(true);

		var outcome = service.evaluate(USER);

		assertThat(outcome.status()).isEqualTo("STAFF_SKIPPED");
		verifyNoInteractions(posts, ai, removePost);
	}

	private void giveVerdict(String acao, double confianca, boolean ambiguo) throws Exception {
		JsonNode veredito = objectMapper.readTree(("{\"acao\":\"%s\",\"ambiguo\":%s,\"confianca\":%s,"
			+ "\"categoria\":\"SPAM\",\"justificativa\":\"flood de posts\"}")
			.formatted(acao, ambiguo, confianca));
		when(ai.triage(anyString(), eq("SPAM"), anyString())).thenReturn(Optional.of(veredito.toString()));
		when(ai.parseVerdict(anyString())).thenReturn(Optional.of(veredito));
	}

	/** Posts distintos (o padrão de flood), todos dentro da janela de 10 minutos. */
	private List<Post> recentPosts(int quantidade, int minutosAtras) {
		List<Post> lista = new ArrayList<>();
		for (int i = 0; i < quantidade; i++) {
			lista.add(post("post_flood_" + i, NOW.minusSeconds(minutosAtras * 60L + i), "Post de teste numero " + i));
		}
		return lista;
	}

	private Post post(String id, Instant createdAt, String content) {
		return Post.publish(new PostId(id), new UserId(USER), PostType.INSIGHT, content, null, List.of(), List.of(),
			null, createdAt);
	}
}
