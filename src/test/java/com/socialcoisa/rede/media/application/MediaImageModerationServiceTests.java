package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Porta do upload: o que bloqueia, o que segue e o que a falha da IA não pode virar.
 *
 * <p>A IA de mídia é a camada que decide a violação; o resto da plataforma (validações de
 * arquivo, moderação de publicação, revisão humana da varredura) continua ativo independente
 * dela. Aqui o contrato é: só o veredito CONFIRMADO (PROIBIDO) bloqueia; indisponibilidade,
 * timeout, resposta inválida, imagem ilegível ou inconclusividade seguem o fluxo sem veto.</p>
 */
class MediaImageModerationServiceTests {

	private final ObjectProvider<ImageContentClassifier> provider = mock();
	private final ImageContentClassifier classifier = mock();
	private final ImageContentReviewer reviewer = mock();

	private MediaImageModerationService service(boolean enabled, double threshold) {
		return new MediaImageModerationService(provider, reviewer, new SimpleMeterRegistry(), enabled, threshold);
	}

	@Test
	void permitsImagesBelowThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(0.69);

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();

		verify(classifier).pornographicScore(new byte[] { 1 }, "image/jpeg");
	}

	@Test
	void blocksScoreAtThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/png")).thenReturn(0.70);
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.PROHIBITED));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void allowsAnImageWhenTheCoarseClassifierFlagsItButSemanticReviewDoesNotConfirmIt() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(0.95);
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();

		verify(reviewer).review(new byte[] { 1 });
	}

	@Test
	void allowsPublicationWhenTheSemanticReviewIsInconclusive() throws Exception {
		// IA não concluiu (UNSURE): não é violação confirmada, portanto não bloqueia o envio. A
		// decisão fica para a revisão humana da varredura — não para um veto neste ponto.
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(0.95);
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.UNSURE));

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();

		verify(reviewer).review(new byte[] { 1 });
	}

	@Test
	void allowsPublicationWhenTheClassifierIsUnavailable() throws Exception {
		when(provider.getIfAvailable()).thenReturn(null);

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();

		verify(classifier, never()).pornographicScore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		verify(reviewer, never()).review(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void allowsPublicationWhenTheAnalysisFailsWithoutBlocking() throws Exception {
		// Timeout/falha de infraestrutura na IA: falha técnica não é conteúdo.
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenThrow(new IllegalStateException("timeout"));

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();
	}

	@Test
	void allowsPublicationWhenTheImageCannotBeDecodedByTheAnalyzer() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/webp")).thenThrow(new IOException("ilegivel"));

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/webp"))
			.doesNotThrowAnyException();
	}

	@Test
	void allowsPublicationWhenTheModelResponseIsInvalid() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(Double.NaN);

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();

		verify(reviewer, never()).review(new byte[] { 1 });
	}

	@Test
	void allowsPublicationWhenTheSemanticReviewFails() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(0.95);
		when(reviewer.review(new byte[] { 1 })).thenThrow(new RuntimeException("falha no analisador"));

		assertThatCode(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.doesNotThrowAnyException();
	}

	@Test
	void doesNotClassifyNonImageMedia() {
		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "video/mp4");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void disabledModerationDoesNotLoadOrCallTheClassifier() {
		service(false, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void rejectsInvalidThresholdAtStartup() {
		assertThatThrownBy(() -> service(true, 1.1)).isInstanceOf(IllegalArgumentException.class);
	}

}
