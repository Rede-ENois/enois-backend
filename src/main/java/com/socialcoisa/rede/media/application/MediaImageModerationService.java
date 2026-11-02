package com.socialcoisa.rede.media.application;

import java.io.IOException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.shared.web.ApiException;

/** Enforces the image-content policy before the upload reaches public storage. */
@Service
public class MediaImageModerationService {
	private final ObjectProvider<ImageContentClassifier> classifierProvider;
	private final boolean enabled;
	private final double threshold;

	public MediaImageModerationService(ObjectProvider<ImageContentClassifier> classifierProvider,
			@Value("${rede.media.nsfw.enabled:true}") boolean enabled,
			@Value("${rede.media.nsfw.threshold:0.70}") double threshold) {
		if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("rede.media.nsfw.threshold precisa estar entre 0 e 1.");
		}
		this.classifierProvider = classifierProvider;
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
			double score = classifier.pornographicScore(imageBytes, mimeType);
			if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
				throw new IllegalStateException("O classificador retornou uma pontuação inválida.");
			}
			if (score >= threshold) {
				throw new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED",
					"Esta imagem parece conter conteúdo pornográfico e não pode ser enviada.");
			}
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
