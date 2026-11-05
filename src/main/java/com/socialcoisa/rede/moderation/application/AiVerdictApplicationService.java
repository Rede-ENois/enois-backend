package com.socialcoisa.rede.moderation.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.moderation.ai.ModerationAiService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Executa os vereditos PENALIZAR gravados pela pre-triagem: para cada denuncia PENDING com
 * ai_triage de acao PENALIZAR (que ja passou no gate do parse), aplica a sancao. Revalida o
 * gate aqui — o JSON gravado nao pode valer mais do que a politica do dia.
 */
@Service
public class AiVerdictApplicationService {

	private final ReportRepository reports;
	private final SanctionService sanctions;
	private final ModerationLearningService learning;
	private final ObjectMapper objectMapper;

	public AiVerdictApplicationService(ReportRepository reports, SanctionService sanctions,
			ModerationLearningService learning, ObjectMapper objectMapper) {
		this.reports = reports;
		this.sanctions = sanctions;
		this.learning = learning;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public int applyPendingVerdicts(int limit) {
		int applied = 0;
		List<Report> pending = reports.findByStatus(ReportStatus.PENDING, limit);
		for (Report report : pending) {
			if (!StringUtils.hasText(report.aiTriage())) {
				continue;
			}
			// Denúncia de chamada NUNCA é penalizada automaticamente: a prova é uma captura enviada
			// por quem denuncia, e uma imagem escolhida pela parte não basta para restringir a outra.
			// A triagem da IA orienta; a decisão é de uma pessoa.
			if (report.targetType() == com.socialcoisa.rede.moderation.domain.TargetType.CALL) {
				continue;
			}
			try {
				JsonNode verdict = objectMapper.readTree(report.aiTriage());
				if (!"PENALIZAR".equals(verdict.path("acao").asString())
						|| !ModerationAiService.canAutoPenalize(verdict)) {
					continue;
				}
				String motive = verdict.path("justificativa").asString();
				if (!StringUtils.hasText(motive)) {
					motive = verdict.path("categoria").asString();
				}
				if (sanctions.applyForReport(report, motive)) {
					learning.record(report, "AUTO_PENALIZE", "AI", "ai-triage");
					applied++;
				}
			}
			catch (Exception invalid) {
				// triagem corrompida ou fora da politica: denuncia segue para revisao humana
			}
		}
		return applied;
	}
}
