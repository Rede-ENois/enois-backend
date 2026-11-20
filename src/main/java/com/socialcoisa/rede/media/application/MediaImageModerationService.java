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
	private static final org.slf4j.Logger log =
		org.slf4j.LoggerFactory.getLogger(MediaImageModerationService.class);

	private final ObjectProvider<ImageContentClassifier> classifierProvider;
	private final ImageContentReviewer reviewer;
	private final boolean enabled;
	private final double threshold;
	/**
	 * Falha técnica não bloqueia o upload (#486).
	 *
	 * <p>O uso pretendido é deixar a imagem passar para as demais camadas quando o próprio mecanismo
	 * falha (classificador ausente, erro de inferência, revisor sem resposta). Isso NÃO vale para
	 * conteúdo que o sistema reconhece como proibido — esse continua barrado. Ligado por padrão
	 * porque só a análise NSFW tem continuidade em caso de falha; as outras validações seguem iguais.</p>
	 */
	private final boolean failOpen;
	/** Observação pura do custo da etapa (#710); sem registry, nada é registrado. */
	private final MediaUploadStageMetrics stageMetrics;

	public MediaImageModerationService(ObjectProvider<ImageContentClassifier> classifierProvider,
			ImageContentReviewer reviewer,
			@Value("${rede.media.nsfw.enabled:true}") boolean enabled,
			@Value("${rede.media.nsfw.threshold:0.70}") double threshold,
			@Value("${rede.media.nsfw.fail-open:true}") boolean failOpen,
			MediaUploadStageMetrics stageMetrics) {
		if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("rede.media.nsfw.threshold precisa estar entre 0 e 1.");
		}
		this.classifierProvider = classifierProvider;
		this.reviewer = reviewer;
		this.enabled = enabled;
		this.threshold = threshold;
		this.failOpen = failOpen;
		this.stageMetrics = stageMetrics;
	}

	public void verifyBeforeStorage(byte[] imageBytes, String mimeType) {
		String normalized = mimeType == null ? "" : mimeType.trim().toLowerCase(java.util.Locale.ROOT);
		if (!enabled || !normalized.startsWith("image/") || normalized.startsWith("image/svg+xml")) {
			return;
		}

		ImageContentClassifier classifier = classifierProvider.getIfAvailable();
		if (classifier == null) {
			// Sem classificador instalado não há análise a fazer: a falha técnica segue para as
			// demais camadas em vez de barrar uma publicação legítima (#486).
			if (failOpen) {
				recordInconclusive();
				return;
			}
			throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
				"A verificação de imagens está indisponível. Tente novamente mais tarde.");
		}
		long startedAt = System.nanoTime();
		try {
			ImageContentClassifier.Assessment assessment = classifier.assess(imageBytes, mimeType);
			double score = assessment.score();
			if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
				throw new IllegalStateException("O classificador retornou uma pontuação inválida.");
			}
			if (score >= threshold) {
				// An animation is reviewed on the frame that was flagged, not on its first frame.
				byte[] reviewed = assessment.reviewFrame() != null ? assessment.reviewFrame() : imageBytes;
				// Sem resposta do revisor é INCERTEZA, não aprovação: o classificador já apontou o
				// risco, e mandar a imagem para o storage público porque o revisor não respondeu
				// seria a única barreira existente falhando aberta.
				Result result = reviewer.review(reviewed).orElse(Result.UNSURE);
				if (result == Result.PROHIBITED) {
					stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.BLOCKED, startedAt);
					throw new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED",
						"Esta imagem parece conter conteúdo pornográfico e não pode ser enviada.");
				}
				if (result == Result.UNSURE) {
					// Classificador apontou risco, mas o revisor não conseguiu decidir. Com a
					// continuidade por falha técnica, a imagem não é aprovada nem confirmada aqui:
					// segue para as demais camadas, com o caso registrado como inconclusivo (#486).
					if (failOpen) {
						recordInconclusive();
						return;
					}
					stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.BLOCKED, startedAt);
					throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
						"Não foi possível confirmar a verificação desta imagem.");
				}
			}
			stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.OK, startedAt);
		}
		catch (ImageContentClassifier.TooLargeToVerifyException failure) {
			stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.BLOCKED, startedAt);
			throw new ApiException(422, "IMAGE_TOO_LARGE_TO_VERIFY",
				"Esta imagem é grande ou longa demais para ser verificada. Envie uma versão menor.");
		}
		catch (IOException failure) {
			// Não decodificou ≠ imagem limpa. Antes de barrar o upload, há uma segunda chance: os
			// formatos que o classificador não lê (AVIF/HEIF, sequência de quadros) costumam ser
			// imagem parada para o revisor semântico, que decide sobre o sentido da imagem, não sobre
			// o formato. Só passa quando ele diz LIMPO; PROHIBITED barra e UNSURE — ou revisor sem
			// resposta — segue para as demais camadas com a continuidade por falha técnica (#486).
			Result result = reviewer.review(imageBytes).orElse(Result.UNSURE);
			if (result == Result.PROHIBITED) {
				stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.BLOCKED, startedAt);
				throw new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED",
					"Esta imagem parece conter conteúdo pornográfico e não pode ser enviada.");
			}
			if (result == Result.UNSURE) {
				if (failOpen) {
					recordInconclusive();
					return;
				}
				stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.BLOCKED, startedAt);
				throw new ApiException(422, "IMAGE_CANNOT_BE_VERIFIED",
					"Não foi possível verificar esta imagem. Tente outro arquivo.");
			}
			stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.OK, startedAt);
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			if (!failOpen) {
				stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.ERROR, startedAt);
				throw new ApiException(503, "IMAGE_MODERATION_UNAVAILABLE",
					"A verificação de imagens está indisponível. Tente novamente mais tarde.");
			}
			log.warn("nsfw_moderation_inconclusive cause={}", failure.getClass().getName());
			recordInconclusive();
		}
	}

	/**
	 * Registra a análise que não conseguiu decidir por falha técnica e libera a imagem para as
	 * demais camadas (#486). Entra na série `nsfw` como `error`, o resultado técnico de sempre —
	 * nada é gravado como bloqueio, porque não houve reconhecimento de conteúdo proibido.
	 */
	private void recordInconclusive() {
		stageMetrics.record("nsfw", MediaUploadStageMetrics.Outcome.ERROR, System.nanoTime());
	}
}
