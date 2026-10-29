package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.ai.MediaSafetyAnalyzer;
import com.socialcoisa.rede.moderation.application.port.out.MediaSafetyCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * A barreira em si: o que acontece quando o classificador diz que a imagem é explícita.
 *
 * <p>O teste que importa é o de 2026-10-29: conteúdo explícito publicado, imagem derrubada e conta
 * banida. Os outros garantem que a automação NÃO age quando não tem certeza — falso positivo aqui
 * apaga post de gente honesta.</p>
 */
class MediaSafetyServiceTests {

	private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");
	private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);

	private MediaSafetyCheckRepository checks;
	private ReportedMediaReader mediaReader;
	private MediaSafetyAnalyzer analyzer;
	private MediaAssetRepository assets;
	private PostRepository posts;
	private ModerationRemovePostUseCase removePost;
	private ModeratorService moderators;
	private UserProfileRepository profiles;

	private MediaSafetyService service;

	@BeforeEach
	void preparar() {
		checks = mock(MediaSafetyCheckRepository.class);
		mediaReader = mock(ReportedMediaReader.class);
		analyzer = mock(MediaSafetyAnalyzer.class);
		assets = mock(MediaAssetRepository.class);
		posts = mock(PostRepository.class);
		removePost = mock(ModerationRemovePostUseCase.class);
		moderators = mock(ModeratorService.class);
		profiles = mock(UserProfileRepository.class);
		service = new MediaSafetyService(checks, mediaReader, analyzer, assets, posts, removePost, moderators,
			profiles, RELOGIO, true);

		when(checks.findById(anyString())).thenReturn(Optional.empty());
		when(mediaReader.imagesOf(anyString(), anyString(), anyInt())).thenReturn(List.of(new byte[] { 1, 2, 3 }));
		when(assets.findById("media_1")).thenReturn(Optional.of(midiaDo("user_1")));
	}

	@Test
	void explicitImageIsRemovedFromEveryPostAndTheAccountIsBanned() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.EXPLICIT, "CRITICAL", 0.95, "nudez visivel")));
		when(posts.findByMediaId("media_1")).thenReturn(List.of(post("post_1"), post("post_2")));
		when(profiles.findById("user_1")).thenReturn(Optional.of(perfil("fulano")));
		when(moderators.sanctionUser(anyString(), eq("fulano"), eq(SanctionType.PERMABAN), anyString()))
			.thenReturn(new ModeratorService.SanctionResult("user_1", "fulano", "PERMABAN", "ACTIVE"));

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		assertThat(resultado.removedPosts()).isEqualTo(2);
		// A imagem sai do ar em TODOS os posts que a usam, não só no que disparou.
		verify(removePost).removeAsModerator(eq(MediaSafetyService.ACTOR), eq("post_1"), anyString());
		verify(removePost).removeAsModerator(eq(MediaSafetyService.ACTOR), eq("post_2"), anyString());
		verify(moderators).sanctionUser(eq(MediaSafetyService.ACTOR), eq("fulano"), eq(SanctionType.PERMABAN),
			anyString());
		verify(moderators).sanitizeUser(eq(MediaSafetyService.ACTOR), eq("fulano"), anyString());
	}

	@Test
	void theLedgerRecordsTheVerdictForAudit() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.EXPLICIT, "HIGH", 0.9, "nudez")));
		when(posts.findByMediaId("media_1")).thenReturn(List.of());

		service.check("media_1");

		verify(checks).save(any(MediaSafetyCheck.class));
	}

	@Test
	void anUncertainVerdictRecordsButDoesNotPunish() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.EXPLICIT, "HIGH", 0.4, "talvez")));

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		verify(moderators, never()).sanctionUser(anyString(), anyString(), any(), anyString());
	}

	@Test
	void aSafeImageCostsNothing() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.SAFE, "LOW", 0.99, "pessoa vestida")));

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(posts, never()).findByMediaId(anyString());
		verify(moderators, never()).sanctionUser(anyString(), anyString(), any(), anyString());
	}

	@Test
	void mediaWithoutAVerdictIsNotReDownloadedEveryRound() {
		// Modelo fora do ar ou imagem ilegível: registra UNSURE para a varredura não repetir o custo.
		when(analyzer.analyze(any())).thenReturn(Optional.empty());

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("NO_VERDICT");
		verify(checks).save(any(MediaSafetyCheck.class));
		verify(moderators, never()).sanctionUser(anyString(), anyString(), any(), anyString());
	}

	@Test
	void alreadyCheckedMediaIsNotAnalyzedAgain() {
		when(checks.findById("media_1")).thenReturn(Optional.of(MediaSafetyCheck.of("media_1", "user_1",
			MediaSafetyVerdict.SAFE, "LOW", 0.99, "ok", "modelo", AGORA)));

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("ALREADY_CHECKED");
		verify(analyzer, never()).analyze(any());
		verify(mediaReader, never()).imagesOf(anyString(), anyString(), anyInt());
	}

	@Test
	void aDisabledSwitchChecksNothing() {
		MediaSafetyService desligado = new MediaSafetyService(checks, mediaReader, analyzer, assets, posts, removePost,
			moderators, profiles, RELOGIO, false);

		assertThat(desligado.check("media_1").status()).isEqualTo("DISABLED");
		verify(analyzer, never()).analyze(any());
	}

	private static MediaAsset midiaDo(String ownerId) {
		return MediaAsset.pending("media_1", new UserId(ownerId), "foto.jpg", "image/jpeg", 1000, "post",
			"media_1.jpg", "https://cdn.test/media_1.jpg", AGORA, AGORA.plusSeconds(3600));
	}

	private static Post post(String id) {
		return Post.publish(new PostId(id), new UserId("user_1"), com.socialcoisa.rede.feed.domain.PostType.INSIGHT,
			"oi", null, List.of(), List.of("media_1"), null, AGORA);
	}

	private static UserProfile perfil(String username) {
		return UserProfile.create("user_1", username, "fulano@test.com", "Fulano", "bio", AGORA);
	}
}
