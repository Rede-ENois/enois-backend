package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.shared.web.ApiException;

class MediaImageModerationServiceTests {

	private final ObjectProvider<ImageContentClassifier> provider = mock();
	private final ImageContentClassifier classifier = mock();
	private final ImageContentReviewer reviewer = mock();
	private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
		new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

	private MediaImageModerationService service(boolean enabled, double threshold) {
		return new MediaImageModerationService(provider, reviewer, enabled, threshold,
			comRegistry(registry));
	}

	/** Helper com o registry da prova; sem registry, nada é registrado. */
	private static MediaUploadStageMetrics comRegistry(io.micrometer.core.instrument.MeterRegistry alvo) {
		return new MediaUploadStageMetrics(new ObjectProvider<>() {
			@Override
			public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
				return alvo;
			}
		});
	}

	private io.micrometer.core.instrument.Timer etapa(String outcome) {
		return registry.find("rede_media_upload_stage").tags("stage", "nsfw", "outcome", outcome).timer();
	}

	@Test
	void permitsImagesBelowThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.69));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verify(classifier).assess(new byte[] { 1 }, "image/jpeg");
	}

	@Test
	void blocksScoreAtThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/png")).thenReturn(still(0.70));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.PROHIBITED));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void allowsAnImageWhenTheCoarseClassifierFlagsItButSemanticReviewDoesNotConfirmIt() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.95));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verify(reviewer).review(new byte[] { 1 });
	}

	@Test
	void reviewsTheFlaggedFrameOfAnAnimationInsteadOfTheOriginalFile() throws Exception {
		byte[] flaggedFrame = { 9, 9 };
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/gif"))
			.thenReturn(new ImageContentClassifier.Assessment(0.95, flaggedFrame));
		when(reviewer.review(flaggedFrame)).thenReturn(Optional.of(Result.PROHIBITED));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/gif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void namesTheRejectionWhenTheImageIsTooLargeToVerify() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/gif"))
			.thenThrow(new ImageContentClassifier.TooLargeToVerifyException("budget"));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/gif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_TOO_LARGE_TO_VERIFY");
		verifyNoInteractions(reviewer);
	}

	@Test
	void doesNotApproveAnImageWhenSemanticReviewIsInconclusive() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.95));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.UNSURE));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void doesNotApproveAFlaggedImageWhenTheReviewerDoesNotAnswer() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.95));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void doesNotClassifyNonImageMediaOrSvg() {
		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "video/mp4");
		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/svg+xml");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void disabledModerationDoesNotLoadOrCallTheClassifier() {
		service(false, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void failsClosedWhenClassifierBeanIsMissing() {
		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void failsClosedWhenClassifierCannotDecodeTheImage() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/webp"))
			.thenThrow(new IOException("invalid image"));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.UNSURE));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/webp"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CANNOT_BE_VERIFIED");
	}

	/**
	 * A forma que o classificador não lê (AVIF/HEIF, sequência de quadros) não é imagem limpa: o
	 * revisor semântico é a segunda chance, e ele decide só pelo sentido da imagem.
	 */
	@Test
	void allowsADecodeFailureOnlyWhenTheSemanticReviewerSaysItIsClean() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/avif"))
			.thenThrow(new IOException("no decoder"));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/avif");

		verify(reviewer).review(new byte[] { 1 });
	}

	@Test
	void blocksADecodeFailureThatTheSemanticReviewerCallsProhibited() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/avif"))
			.thenThrow(new IOException("no decoder"));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.PROHIBITED));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/avif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void failsClosedWhenTheReviewerDoesNotAnswerAfterADecodeFailure() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/avif"))
			.thenThrow(new IOException("no decoder"));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/avif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CANNOT_BE_VERIFIED");
	}

	@Test
	void failsClosedWhenClassifierReturnsInvalidScore() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(Double.NaN));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void rejectsInvalidThresholdAtStartup() {
		assertThatThrownBy(() -> service(true, 1.1)).isInstanceOf(IllegalArgumentException.class);
	}

	private static ImageContentClassifier.Assessment still(double score) {
		return new ImageContentClassifier.Assessment(score, null);
	}

	@Test
	void etapaOkEApareceNaSerie() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.10));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		assertThat(etapa("ok")).isNotNull();
		assertThat(etapa("ok").count()).isEqualTo(1);
	}

	@Test
	void conteudoRecusadoFicaNaSerieComoBlockedSemDeixarDeBloquear() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/png")).thenReturn(still(0.99));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.PROHIBITED));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");

		assertThat(etapa("blocked")).isNotNull();
		assertThat(etapa("blocked").count()).isEqualTo(1);
	}

	@Test
	void falhaTecnicaFicaNaSerieComoErrorSemDeixarDeSer503() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/png"))
			.thenThrow(new IllegalStateException("sessao do modelo estourou"));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");

		assertThat(etapa("error")).isNotNull();
		assertThat(etapa("error").count()).isEqualTo(1);
	}
}
