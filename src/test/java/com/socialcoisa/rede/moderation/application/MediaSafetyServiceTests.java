package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import com.socialcoisa.rede.colagem.application.port.in.ColagemModerationPort;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.ai.MediaSafetyAnalyzer;
import com.socialcoisa.rede.moderation.application.SafetyPorts.AnalysedPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.DocumentPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.SafetyLedger;
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
	private ColagemModerationPort colagem;
	private ModeratorService moderators;
	private UserProfileRepository profiles;
	private AnalysedPieces pieces;
	private MediaSafetyPendingChecks documents;
	private DocumentPieces retirement;
	private SafetyPorts.SafetyLedger ledger;

	private MediaSafetyService service;

	@BeforeEach
	void preparar() {
		checks = mock(MediaSafetyCheckRepository.class);
		mediaReader = mock(ReportedMediaReader.class);
		analyzer = mock(MediaSafetyAnalyzer.class);
		assets = mock(MediaAssetRepository.class);
		posts = mock(PostRepository.class);
		removePost = mock(ModerationRemovePostUseCase.class);
		colagem = mock(ColagemModerationPort.class);
		moderators = mock(ModeratorService.class);
		profiles = mock(UserProfileRepository.class);
		pieces = mock(AnalysedPieces.class);
		documents = mock(MediaSafetyPendingChecks.class);
		retirement = mock(DocumentPieces.class);
		ledger = mock(SafetyPorts.SafetyLedger.class);
		service = new MediaSafetyService(checks, mediaReader, analyzer, assets, posts, removePost, colagem,
			moderators, profiles, pieces, documents, retirement, ledger, RELOGIO, true);

		when(checks.findById(anyString())).thenReturn(Optional.empty());
		// Imagem: a peça é a própria mídia.
		when(pieces.piecesOf(anyString())).thenAnswer(invocation -> {
			String mediaId = invocation.getArgument(0);
			MediaAsset asset = assets.findById(mediaId).orElse(null);
			return asset == null ? List.of() : List.of(new PageRef(asset.id(), asset.mimeType()));
		});
		when(ledger.ownerOf(anyString())).thenReturn("user_1");
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
	void videoIsRecordedAsUnsupportedWithoutCallingTheImageReaderOrClassifier() {
		when(assets.findById("media_1")).thenReturn(Optional.of(midiaDo("user_1", "video/mp4")));

		MediaSafetyService.Outcome resultado = service.check("media_1");

		assertThat(resultado.status()).isEqualTo("UNSUPPORTED_MEDIA");
		verify(mediaReader, never()).imagesOf(anyString(), anyString(), anyInt());
		verify(analyzer, never()).analyze(any());
		verify(checks).save(any(MediaSafetyCheck.class));
	}

	@Test
	void pdfIsCheckedPageByPage() {
		when(assets.findById("media_p1")).thenReturn(Optional.of(pagina("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.SAFE, "LOW", 0.99, "pagina de texto")));

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		// O arquivo PDF nunca vai ao classificador: ele olha cada folha desenhada.
		verify(mediaReader).imagesOf("MEDIA", "media_p1", 1);
		verify(mediaReader).imagesOf("MEDIA", "media_p2", 1);
		verify(mediaReader, never()).imagesOf("MEDIA", "media_pdf", 1);
		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(retirement, never()).retire(anyString());
	}

	@Test
	void anExplicitPageTakesTheWholeDocumentOut() {
		when(assets.findById("media_p1")).thenReturn(Optional.of(pagina("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		when(analyzer.analyze(any()))
			.thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(MediaSafetyVerdict.SAFE, "LOW", 0.99, "texto")))
			.thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(MediaSafetyVerdict.EXPLICIT, "CRITICAL", 0.95,
				"nudez")));
		when(checks.save(any())).thenAnswer(invocation -> {
			MediaSafetyCheck check = invocation.getArgument(0);
			when(checks.findById(check.mediaId())).thenReturn(Optional.of(check));
			return check;
		});
		when(checks.findById("media_p1"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(seguro("media_p1")));
		when(checks.findById("media_p2"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(explicito("media_p2")));

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		// Uma folha contamina o documento: o PDF inteiro sai, não só a página.
		verify(retirement).retire("media_pdf");
		verify(retirement, never()).retire("media_p1");
		verify(retirement, never()).retire("media_p2");
	}

	@Test
	void pageWithRecordedVerdictIsEnforcedWithoutAskingTheModelAgain() {
		when(assets.findById("media_p1")).thenReturn(Optional.of(pagina("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		// O registro de uma folha barrada vale pelo que foi decidido: a varredura não precisa do modelo.
		when(checks.findById("media_p1")).thenReturn(Optional.of(explicito("media_p1")));
		when(checks.findById("media_p2")).thenReturn(Optional.of(seguro("media_p2")));

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		verify(analyzer, never()).analyze(any());
		verify(retirement).retire("media_pdf");
	}

	@Test
	void aPageWaitingForDeletionStillTakesTheDocumentOut() {
		// Pedido de remoção não é remoção: enquanto a folha barrada pode ser aberta, o documento
		// inteiro precisa sair.
		when(assets.findById("media_p1")).thenReturn(Optional.of(paginaComRemocaoPedida("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		when(checks.findById("media_p1")).thenReturn(Optional.of(explicito("media_p1")));
		when(checks.findById("media_p2")).thenReturn(Optional.of(seguro("media_p2")));

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		verify(retirement).retire("media_pdf");
	}

	@Test
	void aPageStillBeingCheckedIsNotLookedAtAgain() {
		when(assets.findById("media_p1")).thenReturn(Optional.of(pagina("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		when(checks.findById("media_p1")).thenReturn(Optional.of(MediaSafetyCheck.of("media_p1", "user_1",
			MediaSafetyVerdict.SAFE, "LOW", 0.99, "ok", "modelo", AGORA)));

		service.check("media_pdf");

		verify(mediaReader, never()).imagesOf(anyString(), eq("media_p1"), anyInt());
		verify(mediaReader).imagesOf("MEDIA", "media_p2", 1);
	}

	@Test
	void aPageAlreadyBarredInTheLedgerTakesTheDocumentOutWithoutAskingTheModelAgain() {
		when(assets.findById("media_p1")).thenReturn(Optional.of(pagina("media_p1")));
		when(assets.findById("media_p2")).thenReturn(Optional.of(pagina("media_p2")));
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of(new PageRef("media_p1", "image/png"),
			new PageRef("media_p2", "image/png")));
		when(checks.findById("media_p1")).thenReturn(Optional.of(seguro("media_p1")));
		when(checks.findById("media_p2")).thenReturn(Optional.of(explicito("media_p2")));

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		// Registro no ledger não é takedown: a folha barrada que continua no ar passa pela barreira.
		assertThat(resultado.status()).isEqualTo("ENFORCED");
		assertThat(resultado.verdict()).isEqualTo(MediaSafetyVerdict.EXPLICIT);
		verify(retirement).retire("media_pdf");
		verify(analyzer, never()).analyze(any());
		verify(mediaReader, never()).imagesOf(anyString(), anyString(), anyInt());
	}

	@Test
	void aDocumentWithoutPagesIsRecordedAsUnsureAndCannotBeReleased() {
		when(pieces.piecesOf("media_pdf")).thenReturn(List.of());

		MediaSafetyService.Outcome resultado = service.check("media_pdf");

		assertThat(resultado.status()).isEqualTo("NO_VIEW");
		verify(checks).save(any(MediaSafetyCheck.class));
		verify(retirement, never()).retire(anyString());
	}

	@Test
	void theDocumentIsTakenOutEvenWhenTheRemovalFails() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.EXPLICIT, "CRITICAL", 0.95, "nudez")));
		when(posts.findByMediaId("media_1")).thenReturn(List.of());
		doThrow(new IllegalStateException("banco fora")).when(retirement).retire(anyString());

		// Mesmo que a retirada falhe, o veredito sai e o post não fica no ar.
		assertThatThrownBy(() -> service.check("media_1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aDisabledSwitchChecksNothing() {
		MediaSafetyService desligado = new MediaSafetyService(checks, mediaReader, analyzer, assets, posts, removePost,
			colagem, moderators, profiles, pieces, documents, retirement, ledger, RELOGIO, false);

		assertThat(desligado.check("media_1").status()).isEqualTo("DISABLED");
		verify(analyzer, never()).analyze(any());
	}

	private static MediaAsset midiaDo(String ownerId) {
		return midiaDo(ownerId, "image/jpeg");
	}

	private static MediaSafetyCheck seguro(String pieceId) {
		return MediaSafetyCheck.of(pieceId, "user_1", MediaSafetyVerdict.SAFE, "LOW", 0.99, "texto", "stub", AGORA);
	}

	private static MediaSafetyCheck explicito(String pieceId) {
		return MediaSafetyCheck.of(pieceId, "user_1", MediaSafetyVerdict.EXPLICIT, "CRITICAL", 0.95, "nudez", "stub",
			AGORA);
	}

	private static MediaAsset pagina(String id) {
		return MediaAsset.pending(id, new UserId("user_1"), "pagina", "image/png", 1000, null,
			"paginas/media_lote/pagina-1.png", "https://cdn.test/paginas/media_lote/pagina-1.png", AGORA,
			AGORA.plusSeconds(3600));
	}

	/** A folha que já pediu remoção e ainda espera a limpeza: continua no ar até o objeto sair. */
	private static MediaAsset paginaComRemocaoPedida(String id) {
		MediaAsset pagina = pagina(id);
		pagina.requestDeletion(AGORA);
		return pagina;
	}

	private static MediaAsset midiaDo(String ownerId, String mimeType) {
		return MediaAsset.pending("media_1", new UserId(ownerId), "arquivo", mimeType, 1000, "post",
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
