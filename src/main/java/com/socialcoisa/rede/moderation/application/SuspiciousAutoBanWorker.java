package com.socialcoisa.rede.moderation.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.SuspiciousUserReader;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Fecha o ciclo da triagem: quem passou do teto de risco é banido e tem o conteúdo apagado, sem
 * esperar alguém abrir o painel.
 *
 * Decisão da dona (2026-10-22), depois do ataque de cadastros em série: risco ACIMA do teto →
 * banimento + sanitização dos posts/comentários. Empate no teto fica para o julgamento humano de
 * propósito — a automação só age no que passou dele. A triagem continua existindo para o que fica
 * ABAIXO do teto e para o que a automação não resolve.
 *
 * O teto era 60 e passou a 80 em 2026-10-30 (decisão da dona: a automação estava banindo conta que
 * só somava sinais fracos — nome repetido, perfil vazio, conta nova). Continua ACIMA do teto, não
 * "a partir de": 80 exato fica para o humano. Ajustável por `MODERATION_AUTO_BAN_RISK`.
 *
 * Cuidados que a automação NÃO dispensa:
 * - nunca toca em admin ou moderador (o próprio serviço de triagem os exclui);
 * - só olha a janela recente da triagem (nada de sair punindo conta antiga por heurística);
 * - uma conta por vez, no fluxo normal de sanção (sessões revogadas, trilha gravada);
 * - já banida = pula (a sanitização não roda duas vezes);
 * - IPs que acumularam contas banidas no mesmo ciclo entram na lista de bloqueio, que é o que
 *   impede o próximo lote de nascer;
 * - todo item é logado com o risco e os sinais que o dispararam.
 */
@Component
public class SuspiciousAutoBanWorker {

	private static final Logger LOG = LoggerFactory.getLogger(SuspiciousAutoBanWorker.class);
	/** Referência da sanção: não é uma pessoa, é a automação — aparece na trilha do banco. */
	static final String ACTOR = "automod:risco";
	private static final int MAX_SCAN = 200;
	private static final String PATTERN_REASON =
		"Conta com o padrão do ataque em série (moderação automática de contenção).";

	private final SuspiciousUserService suspiciousUsers;
	private final ModeratorService moderators;
	private final AuthSessionRepository sessions;
	private final IpBlockService ipBlocks;
	private final SuspiciousUserReader reader;
	private final List<String> patterns;
	private final java.time.Clock clock;
	private final boolean enabled;
	private final int threshold;
	private final int maxPerRun;
	private final int ipsToInspect;
	private final int accountsPerIpToBlock;
	// Último resultado da rodada: é o que o painel mostra para não ficarmos no escuro
	// (o post-mortem de 22/10 apontou "não sabemos o que aconteceu" como o problema).
	private volatile java.time.Instant lastRunAt;
	private volatile Report lastReport;
	private volatile String lastError;

	public SuspiciousAutoBanWorker(SuspiciousUserService suspiciousUsers, ModeratorService moderators,
			AuthSessionRepository sessions, IpBlockService ipBlocks, SuspiciousUserReader reader,
			java.time.Clock clock,
			@Value("${rede.security.suspicious.name-patterns:probe}") String patternList,
		@Value("${rede.moderation.auto-ban.enabled:true}") boolean enabled,
		@Value("${rede.moderation.auto-ban.risk:80}") int threshold,
		@Value("${rede.moderation.auto-ban.max-per-run:50}") int maxPerRun,
			@Value("${rede.moderation.auto-ban.ips-per-account:3}") int ipsToInspect,
			@Value("${rede.moderation.auto-ban.accounts-per-ip:3}") int accountsPerIpToBlock) {
		this.suspiciousUsers = suspiciousUsers;
		this.moderators = moderators;
		this.sessions = sessions;
		this.ipBlocks = ipBlocks;
		this.reader = reader;
		this.clock = clock;
		this.patterns = java.util.Arrays.stream(patternList.split(","))
			.map(String::trim)
			.filter(pattern -> !pattern.isEmpty())
			.map(pattern -> pattern.toLowerCase(java.util.Locale.ROOT))
			.toList();
		this.enabled = enabled;
		this.threshold = Math.max(1, Math.min(threshold, 200));
		this.maxPerRun = Math.max(1, Math.min(maxPerRun, 200));
		this.ipsToInspect = Math.max(1, Math.min(ipsToInspect, 10));
		this.accountsPerIpToBlock = Math.max(2, Math.min(accountsPerIpToBlock, 100));
	}

	// Contenção rápida: 1 min depois do boot e a cada 3 min. A varredura olha só as 500 contas
	// mais recentes da janela, então rodar miúdo custa pouco — e o ataque não cresce por 15 min.
	@Scheduled(initialDelayString = "${rede.moderation.auto-ban.initial-delay-ms:15000}",
			fixedDelayString = "${rede.moderation.auto-ban.interval-ms:180000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			// Contenção DIRETA do padrão conhecido: sem score, sem exceção de equipe. Roda 15s
			// depois do boot e a cada ciclo, mesmo que a triagem por risco falhe.
			purgeAttackPatterns();
			Report report = sweep();
			lastReport = report;
			lastError = null;
		}
		catch (RuntimeException failure) {
			lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
			LOG.error("Triagem automática falhou nesta rodada: {}", failure.getMessage());
		}
		finally {
			lastRunAt = clock.instant();
		}
	}

	/** Estado da automação para o painel: rodou quando, o que fez e o último erro (se houve). */
	public Status status() {
		Report report = lastReport;
		java.time.Instant run = lastRunAt;
		return new Status(enabled, threshold, run == null ? null : run.toString(),
			report == null ? 0 : report.banned(), report == null ? 0 : report.failed(),
			report == null ? java.util.List.of() : report.blockedIps(), lastError);
	}

	/** O teto que a automação está usando AGORA (o configurado, já limitado a [1, 200]). */
	public record Status(boolean enabled, int riskCeiling, String lastRunAt, int banned, int failed,
			java.util.List<String> blockedIps, String lastError) {
	}

	/**
	 * Bane e limpa TODA conta cujo nome/username carrega o padrão do ataque — inclusive conta
	 * marcada como equipe (foi assim que a conta do atacante escapou em 22/10).
	 *
	 * Não usa o score de propósito: se a triagem por risco falhar, esta varredura funciona sozinha.
	 */
	public int purgeAttackPatterns() {
		// O portão de liga/desliga é o do agendador (run); aqui a varredura pode ser chamada direto.
		if (patterns.isEmpty()) {
			return 0;
		}
		var since = clock.instant().minus(java.time.Duration.ofDays(365));
		int purged = 0;
		for (var candidate : reader.findMatching(patterns, since, 200)) {
			try {
				var result = moderators.sanctionUser(ACTOR, candidate.username(), SanctionType.PERMABAN,
					PATTERN_REASON);
				if (!"ALREADY_BANNED".equals(result.status())) {
					moderators.sanitizeUser(ACTOR, candidate.username(), PATTERN_REASON);
					purged++;
					LOG.warn("Padrão de ataque: conta {} banida e conteúdo limpo", candidate.username());
				}
			}
			catch (RuntimeException failure) {
				LOG.warn("Padrão de ataque: falha ao banir {}: {}", candidate.username(), failure.getMessage());
			}
		}
		return purged;
	}

	/**
	 * Uma passada da automação. Devolve o relatório (o que foi banido, sanitizado, o que falhou e
	 * os IPs bloqueados) — é o que os testes usam e o que fica no log.
	 */
	public Report sweep() {
		var triage = suspiciousUsers.list(MAX_SCAN, 0);
		List<Ban> bans = new ArrayList<>();
		int failed = 0;
		for (var target : triage.items()) {
			if (bans.size() >= maxPerRun) {
				break;
			}
			if (target.riskScore() <= threshold) {
				continue;
			}
			if (target.staff() && !target.signals().contains("NOME_SUSPEITO")) {
				LOG.warn("Auto-ban: {} tem risco {} mas é da equipe — automação não pune; revise no painel.",
					target.username(), target.riskScore());
				continue;
			}
			if (target.staff()) {
				// Equipe COM a assinatura do ataque não é equipe: é conta comprometida (o atacante
				// de 22/10 usou uma conta que a triagem tratava como moderadora para não ser punida).
				LOG.error("Auto-ban: {} tem selo de equipe E assinatura de ataque — punindo mesmo assim.",
					target.username());
			}
			String reason = reasonFor(target);
			try {
			var result = moderators.sanctionUser(ACTOR, target.username(), SanctionType.SUSPENDED, reason);
			if ("ALREADY_BANNED".equals(result.status())) {
					continue;
				}
				List<String> ips = sessions.findRecentIpsByUser(result.userId(), ipsToInspect);
				moderators.sanitizeUser(ACTOR, target.username(), reason);
				bans.add(new Ban(target.username(), target.riskScore(), ips));
				LOG.warn("Auto-ban: {} banida e sanitizada (risco {}, sinais {})",
					target.username(), target.riskScore(), target.signals());
			}
			catch (RuntimeException failure) {
				failed++;
				LOG.warn("Auto-ban: falha ao punir {} (risco {}): {}",
					target.username(), target.riskScore(), failure.getMessage());
			}
		}
		List<String> blockedIps = blockRepeatIps(bans);
		LOG.info("Triagem automática: {} banida(s), {} falha(s), IPs bloqueados {}",
			bans.size(), failed, blockedIps);
		return new Report(bans.size(), failed, List.copyOf(blockedIps));
	}

	private String reasonFor(SuspiciousUserService.SuspiciousUserView target) {
		return "Risco automático " + target.riskScore() + " na triagem de contas ("
			+ String.join(", ", target.signals()) + "). Banida e conteúdo removido pela automação.";
	}

	/**
	 * Um IP que gerou várias contas banidas no mesmo ciclo é a assinatura do ataque em série:
	 * bloquear o IP impede o próximo lote (o cadastro e o login checam a lista).
	 */
	private List<String> blockRepeatIps(List<Ban> bans) {
		Map<String, Integer> byIp = new HashMap<>();
		for (Ban ban : bans) {
			for (String ip : ban.ips()) {
				byIp.merge(ip, 1, Integer::sum);
			}
		}
		List<String> blocked = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : byIp.entrySet()) {
			if (entry.getValue() < accountsPerIpToBlock) {
				continue;
			}
			try {
				ipBlocks.block(ACTOR, entry.getKey(),
					entry.getValue() + " contas banidas automaticamente por risco");
				blocked.add(entry.getKey());
				LOG.warn("Auto-ban: IP {} bloqueado ({} contas banidas)", entry.getKey(), entry.getValue());
			}
			catch (ApiException alreadyBlocked) {
				// 409 = já estava bloqueado; não é erro da rodada.
			}
			catch (RuntimeException failure) {
				LOG.warn("Auto-ban: não foi possível bloquear o IP {}: {}", entry.getKey(), failure.getMessage());
			}
		}
		return blocked;
	}

	public record Ban(String username, int risk, List<String> ips) {
	}

	public record Report(int banned, int failed, List<String> blockedIps) {
	}
}
