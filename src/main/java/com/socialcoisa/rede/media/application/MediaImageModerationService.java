package com.socialcoisa.rede.media.application;

import java.io.IOException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.shared.web.ApiException;

/** Enforces the image-content policy before the upload reaches public storage. */
@Service
public class MediaImageModerationService {
	private final ObjectProvider<ImageContentClassifier> classifierProvider;
	private final ImageContentReviewer reviewer;
	private final boolean enabled;
	private final double threshold;

	public MediaImageModerationService(ObjectProvider<ImageContentClassifier> classifierProvider,
			ImageContentReviewer reviewer,
			@Value("${rede.media.nsfw.enabled:true}") boolean enabled,
			@Value("${rede.media.nsfw.threshold:0.70}") double threshold) {
		if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("rede.media.nsfw.threshold precisa estar entre 0 e 1.");
		}
		this.classifierProvider = classifierProvider;
		this.reviewer = reviewer;
		this.enabled = enabled;
		this.threshold = threshold;
	}

	public void verifyBeforeStorage(byte[] imageBytes, String mimeType) {
		if (!enabled || mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
			return;
		}

		ImageContentClassifier classifier = classifierProvider.getIfAvailable();
		if (classifier == null) {
			throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
				"A verificação de imagens está indisponível. Tente novamente mais tarde.");
		}
		try {
			ImageContentClassifier.Assessment assessment = classifier.assess(imageBytes, mimeType);
			double score = assessment.score();
			if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
				throw new IllegalStateException("O classificador retornou uma pontuação inválida.");
			}
			if (score >= threshold) {
				// An animation is reviewed on the frame that was flagged, not on its first frame.
				byte[] reviewed = assessment.reviewFrame() != null ? assessment.reviewFrame() : imageBytes;
				Result result = reviewer.review(reviewed).orElseThrow(() -> new ApiException(503,
					"IMAGE_MODERATION_UNAVAILABLE", "Não foi possível confirmar a verificação desta imagem."));
				if (result == Result.PROHIBITED) {
					throw new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED",
						"Esta imagem parece conter conteúdo pornográfico e não pode ser enviada.");
				}
				if (result == Result.UNSURE) {
					throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
						"Não foi possível confirmar a verificação desta imagem.");
				}
			}
		}
		catch (ImageContentClassifier.TooLargeToVerifyException failure) {
			throw new ApiException(422, "IMAGE_TOO_LARGE_TO_VERIFY",
				"Esta imagem é grande ou longa demais para ser verificada. Envie uma versão menor.");
		}
		catch (IOException failure) {
			throw new ApiException(422, "IMAGE_CANNOT_BE_VERIFIED",
				"Não foi possível verificar esta imagem. Tente outro arquivo.");
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
				"A verificação de imagens está indisponível. Tente novamente mais tarde.");
		}
	}
}
