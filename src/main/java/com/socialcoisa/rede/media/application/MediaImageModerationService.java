package com.socialcoisa.rede.media.application;

import java.io.IOException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Enforces the image-content policy before the upload reaches public storage.
 *
 * <p><b>Fail-open controlado (camada de classificação):</b> quando a análise de IA não consegue
 * produzir uma decisão confiável — provedor fora do ar, timeout, resposta inválida, imagem em
 * formato que o analisador não suporta, falha interna — o envio NÃO é bloqueado por aqui. Falha
 * técnica de classificação não é prova de conteúdo: o fluxo segue para as demais camadas de
 * segurança (validações de arquivo, moderação de publicação e revisão humana via varredura).</p>
 *
 * <p><b>Falha segura:</b> só bloqueia quando a IA <b>confirma</b> a violação (score acima do
 * threshold + veredito PROIBIDO com confiança suficiente). Resultado inconclusivo, ausência de
 * palpite ou erro viram "não decidido" — registrado, sem veto.</p>
 */
@Service
public class MediaImageModerationService {
	private static final Logger LOG = LoggerFactory.getLogger(MediaImageModerationService.class);

	private final ObjectProvider<ImageContentClassifier> classifierProvider;
	private final ImageContentReviewer reviewer;
	private final Counter approvedByCoarseClassifier;
	private final Counter approvedBySemanticReviewer;
	private final Counter prohibited;
	private final Counter inconclusive;
	private final Counter analysisErrors;
	private final Counter analysesUnavailable;
	private final boolean enabled;
	private final double threshold;

	public MediaImageModerationService(ObjectProvider<ImageContentClassifier> classifierProvider,
			ImageContentReviewer reviewer, MeterRegistry meterRegistry,
			@Value("${rede.media.nsfw.enabled:true}") boolean enabled,
			@Value("${rede.media.nsfw.threshold:0.70}") double threshold) {
		if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
			throw new IllegalArgumentException("rede.media.nsfw.threshold precisa estar entre 0 e 1.");
		}
		this.classifierProvider = classifierProvider;
		this.reviewer = reviewer;
		this.enabled = enabled;
		this.threshold = threshold;
		this.approvedByCoarseClassifier = Counter.builder("rede.moderation.media.nsfw.approved.coarse")
			.description("Imagens liberadas pela classificação aproximada, abaixo do threshold.")
			.register(meterRegistry);
		this.approvedBySemanticReviewer = Counter.builder("rede.moderation.media.nsfw.approved.semantic")
			.description("Imagens liberadas pela revisão semântica após o flag do classificador.")
			.register(meterRegistry);
		this.prohibited = Counter.builder("rede.moderation.media.nsfw.prohibited")
			.description("Conteúdos com violação confirmada bloqueados no upload.")
			.register(meterRegistry);
		this.inconclusive = Counter.builder("rede.moderation.media.nsfw.inconclusive")
			.description("Análises inconclusivas que seguiram o fluxo sem veto.")
			.register(meterRegistry);
		this.analysisErrors = Counter.builder("rede.moderation.media.nsfw.analysis_errors")
			.description("Falhas técnicas da análise (timeout, provedor fora do ar, resposta inválida)."
				+ " Não bloqueiam: seguem para as demais camadas.")
			.register(meterRegistry);
		this.analysesUnavailable = Counter.builder("rede.moderation.media.nsfw.unavailable")
			.description("Tentativas de análise com o classificador indisponível; seguem sem veto.")
			.register(meterRegistry);
	}

	public void verifyBeforeStorage(byte[] imageBytes, String mimeType) {
		if (!enabled || mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
			return;
		}

		ImageContentClassifier classifier = classifierProvider.getIfAvailable();
		if (classifier == null) {
			analysesUnavailable.increment();
			LOG.warn("media_nsfw_classifier_unavailable mime={}", mimeType);
			return;
		}
		try {
			double score = classifier.pornographicScore(imageBytes, mimeType);
			if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
				// Resposta inválida não vira "seguro" nem "proibido": fica como não decidido.
				analysisErrors.increment();
				LOG.warn("media_nsfw_invalid_score mime={}", mimeType);
				return;
			}
			if (score < threshold) {
				approvedByCoarseClassifier.increment();
				LOG.info("media_nsfw_approved_by_coarse_classifier mime={}", mimeType);
				return;
			}
			Result result = reviewer.review(imageBytes).orElse(null);
			if (result == null) {
				// Sem decisão confiável do analisador semântico: não decide, não bloqueia.
				inconclusive.increment();
				LOG.warn("media_nsfw_no_verdict mime={}", mimeType);
				return;
			}
			if (result == Result.PROHIBITED) {
				prohibited.increment();
				throw new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED",
					"Esta imagem parece conter conteúdo pornográfico e não pode ser enviada.");
			}
			if (result == Result.UNSURE) {
				inconclusive.increment();
				LOG.info("media_nsfw_inconclusive mime={}", mimeType);
				return;
			}
			approvedBySemanticReviewer.increment();
			LOG.info("media_nsfw_approved_by_semantic_reviewer mime={}", mimeType);
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (IOException failure) {
			// Imagem ilegível/sem suporte para o analisador: não é veto desta camada.
			analysisErrors.increment();
			LOG.warn("media_nsfw_analysis_failed mime={}", mimeType, failure);
		}
		catch (RuntimeException failure) {
			// Erro interno da IA: falha técnica, não conteúdo. Segue para as demais camadas.
			analysisErrors.increment();
			LOG.error("media_nsfw_analysis_error mime={}", mimeType, failure);
		}
	}
}
