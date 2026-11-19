package com.socialcoisa.rede.shared.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.security.AutoReportEvent;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.ContentDuplicationGuard;

/**
 * O motor central de risco (plano mestre de segurança humana, item 7).
 *
 * <p>Até aqui cada guarda olhava um sinal sozinho e agia sozinho: o de cadência denunciava, o de
 * duplicidade recusava, o de conta nova segurava. O plano pede o contrário — <b>combinar</b> sinais
 * independentes, transformar a soma em um <b>nível</b> e aplicar a resposta correspondente, com o
 * motivo e a versão da regra registrados para que a decisão possa ser reconstruída.
 *
 * <p><b>Por que só roda quando algo já foi detectado:</b> o motor é chamado pelos guardas, e os
 * guardas só disparam com algo real (cadência de robô, texto repetido). Assim a avaliação não
 * custa uma consulta a cada post — ela custa quando há sinal, que é justamente o caso que
 * interessa.
 *
 * <p><b>Modo de observação por padrão</b> ({@code rede.risk.enforce=false}): o motor avalia,
 * registra e escala o que é grave, mas não barra ninguém. O plano pede exatamente isso antes de
 * endurecer regra: "medir os resultados" e "não invente metas numéricas antes de estabelecer uma
 * linha de base com dados reais". A contenção (RESTRICT) existe no código, com o seu teste, e
 * espera a linha de base para entrar em vigor.
 */
@Component
public class RiskEngine {

	private static final Logger LOG = LoggerFactory.getLogger(RiskEngine.class);

	/** Versão da política: entra em toda avaliação, para reconstruir a decisão depois. */
	private static final String RULE_VERSION = "v2";

	/** Intervalo entre duas ocorrências automáticas do mesmo caso (1 hora). */
	private static final long ESCALATION_COOLDOWN_SECONDS = 3_600L;

	private final RiskSubjectReader subjects;
	private final RiskAssessmentWriter assessments;
	private final BehavioralTimingGuard timing;
	private final ContentDuplicationGuard duplication;
	private final ApplicationEventPublisher events;
	private final java.util.Map<String, Long> ultimaEscalada = new java.util.concurrent.ConcurrentHashMap<>();
	private final Clock clock;
	private final Map<RiskSignal, Integer> weights;
	private final int mediumScore;
	private final int highScore;
	private final int criticalScore;
	private final int newAccountHours;
	private final boolean enforce;

	public RiskEngine(RiskSubjectReader subjects, RiskAssessmentWriter assessments,
			BehavioralTimingGuard timing, ContentDuplicationGuard duplication,
			ApplicationEventPublisher events, Clock clock,
			@Value("${rede.risk.weights.robotic-cadence:3}") int roboticCadenceWeight,
			@Value("${rede.risk.weights.duplicate-content:2}") int duplicateContentWeight,
			@Value("${rede.risk.weights.new-account:1}") int newAccountWeight,
			@Value("${rede.risk.weights.active-sanction:3}") int activeSanctionWeight,
			@Value("${rede.risk.thresholds.medium:2}") int mediumScore,
			@Value("${rede.risk.thresholds.high:5}") int highScore,
			@Value("${rede.risk.thresholds.critical:8}") int criticalScore,
			@Value("${rede.risk.new-account-hours:24}") int newAccountHours,
			@Value("${rede.risk.enforce:false}") boolean enforce) {
		this.subjects = subjects;
		this.assessments = assessments;
		this.timing = timing;
		this.duplication = duplication;
		this.events = events;
		this.clock = clock;
		Map<RiskSignal, Integer> peso = new EnumMap<>(RiskSignal.class);
		peso.put(RiskSignal.ROBOTIC_CADENCE, roboticCadenceWeight);
		peso.put(RiskSignal.DUPLICATE_CONTENT, duplicateContentWeight);
		peso.put(RiskSignal.NEW_ACCOUNT, newAccountWeight);
		peso.put(RiskSignal.ACTIVE_SANCTION, activeSanctionWeight);
		this.weights = Map.copyOf(peso);
		this.mediumScore = mediumScore;
		this.highScore = highScore;
		this.criticalScore = criticalScore;
		this.newAccountHours = newAccountHours;
		this.enforce = enforce;
	}

	/**
	 * Avalia o risco da conta e devolve a avaliação (com o nível e a resposta).
	 *
	 * <p>Os sinais gatilho são os que o guarda já detectou; o motor acrescenta o contexto (conta
	 * nova, sanção ativa) e é isso que separa um post de uma pessoa de um post de um robô.
	 *
	 * <p>Nunca lança: uma falha aqui não pode derrubar a escrita de quem está de boa-fé (o plano:
	 * "os controles fundamentais [...] devem continuar funcionando mesmo quando componentes de
	 * análise avançada estiverem indisponíveis").
	 */
	public RiskAssessment assess(String userId, String action, String content, String... contexto) {
		try {
			List<RiskSignal> sinais = new ArrayList<>();
			if (timing.looksAutomated(userId)) {
				sinais.add(RiskSignal.ROBOTIC_CADENCE);
			}
			if (content != null && duplication.isDuplicate(userId, content, contexto)) {
				sinais.add(RiskSignal.DUPLICATE_CONTENT);
			}
			if (sinais.isEmpty()) {
				// Nenhum sinal: nao ha o que avaliar, e a avaliacao nao custa uma consulta.
				return null;
			}
			Instant agora = clock.instant();
			Instant criada = subjects.createdAtOf(userId);
			if (criada != null && criada.isAfter(agora.minus(Duration.ofHours(newAccountHours)))) {
				sinais.add(RiskSignal.NEW_ACCOUNT);
			}
			if (subjects.hasActiveSanction(userId)) {
				sinais.add(RiskSignal.ACTIVE_SANCTION);
			}
			int score = sinais.stream().mapToInt(s -> weights.getOrDefault(s, 0)).sum();
			RiskLevel level = nivelDe(score);
			RiskDecision decision = decisaoDe(level);
			RiskAssessment avaliacao = new RiskAssessment("risk_" + UUID.randomUUID().toString().replace("-", ""),
				userId, action, level, score, sinais, RULE_VERSION, decision, agora);
			registrar(avaliacao);
			escalarSeGrave(avaliacao, agora.getEpochSecond());
			return avaliacao;
		}
		catch (RuntimeException exception) {
			LOG.warn("avaliacao de risco falhou para user={} action={}: {}", userId, action, exception.getMessage());
			return null;
		}
	}

	/** O nível pelo score: os limiares são configuráveis e a régua é sempre a mesma. */
	RiskLevel nivelDe(int score) {
		if (score >= criticalScore) {
			return RiskLevel.CRITICAL;
		}
		if (score >= highScore) {
			return RiskLevel.HIGH;
		}
		if (score >= mediumScore) {
			return RiskLevel.MEDIUM;
		}
		return RiskLevel.LOW;
	}

	/** A resposta do nível. RESTRICT só entra em vigor com o enforcement ligado. */
	RiskDecision decisaoDe(RiskLevel level) {
		return switch (level) {
			case LOW -> RiskDecision.ALLOW;
			case MEDIUM -> RiskDecision.MONITOR;
			case HIGH -> RiskDecision.ESCALATE;
			case CRITICAL -> enforce ? RiskDecision.RESTRICT : RiskDecision.ESCALATE;
		};
	}

	/**
	 * Entrega à moderação o caso que o motor classificou como grave.
	 *
	 * <p>É a resposta "abrir uma ocorrência para a moderação" da escada do plano: quem decide é
	 * uma pessoa, e o motor entrega o caso já com o nível e os sinais anexados. A denúncia é
	 * automática e NÃO depende de alguém ter denunciado — é o que o plano pede ("o próprio Énóis
	 * deve identificar e encaminhar incidentes proativamente").
	 *
	 * <p>A moderação já recusa denúncia repetida com 409 (o listener trata como best-effort), mas
	 * o motor guarda o próprio intervalo: um post por segundo do mesmo robô viraria uma tentativa
	 * de inserção por segundo, sem ganhar nada.
	 */
	private void escalarSeGrave(RiskAssessment avaliacao, long agoraSegundos) {
		if (avaliacao.level() == RiskLevel.LOW) {
			return;
		}
		Long anterior = ultimaEscalada.get(avaliacao.userId());
		if (anterior != null && agoraSegundos - anterior < ESCALATION_COOLDOWN_SECONDS) {
			return;
		}
		ultimaEscalada.put(avaliacao.userId(), agoraSegundos);
		try {
			events.publishEvent(new AutoReportEvent(avaliacao.userId(), TargetType.USER, ReportReason.SPAM,
				"[risco automatico] Nivel " + avaliacao.level() + " (pontos " + avaliacao.score() + ", sinais "
					+ avaliacao.signals() + ", regra " + avaliacao.ruleVersion() + ", acao " + avaliacao.action()
					+ ")."));
		}
		catch (RuntimeException exception) {
			LOG.warn("nao foi possivel escalar a avaliacao de risco id={}: {}", avaliacao.id(),
				exception.getMessage());
		}
	}

	/** Grava a avaliação (o histórico do risco). Uma falha aqui não impede a escalada. */
	private void registrar(RiskAssessment avaliacao) {
		try {
			assessments.save(avaliacao);
		}
		catch (RuntimeException exception) {
			LOG.warn("nao foi possivel registrar a avaliacao de risco id={}: {}", avaliacao.id(),
				exception.getMessage());
		}
	}

}
