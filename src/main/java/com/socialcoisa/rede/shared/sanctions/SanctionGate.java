package com.socialcoisa.rede.shared.sanctions;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Portão de entrada do enforcement da sanção de moderação.
 *
 * <p>Fica em {@code shared} pelo mesmo motivo do {@link com.socialcoisa.rede.shared.age.AgeGate}:
 * feed, grupos, conversa e blog precisam da mesma resposta sem depender do módulo de moderação,
 * que é o dono da escrita. O código é único, para o front tratar o caso igual em qualquer ponto
 * do app.
 *
 * <p><b>Por que existe:</b> a restrição é uma decisão humana ("não publica nem comenta"). Em
 * 2026-10-21 ela era conferida só no post e no comentário do feed: a mesma conta restrita ainda
 * publicava em grupo, comentava em grupo e mandava mensagem privada — ou seja, continuava
 * alcançando pessoas (e o canal privado é justamente onde a moderação não enxerga).
 */
@Component
public class SanctionGate {

	public static final String CODE = "ACCOUNT_RESTRICTED";

	private static final String REASON_LABEL = "Motivo: ";

	private static final String UNTIL_LABEL = " revisável até ";

	private final SanctionGateReader reader;

	private final Duration suspensionGrace;

	public SanctionGate(SanctionGateReader reader,
			@org.springframework.beans.factory.annotation.Value("${rede.moderation.suspension.grace-days:7}") int graceDays) {
		this.reader = reader;
		this.suspensionGrace = Duration.ofDays(Math.max(1, Math.min(graceDays, 365)));
	}

	/**
	 * Ação de escrita exige conta sem restrição ativa.
	 *
	 * <p>Vale para todo caminho que produz conteúdo ou fala com outra pessoa: post, comentário,
	 * post de grupo, comentário de grupo e mensagem privada. A conta banida nem chega aqui (a
	 * sessão é revogada no banimento); a restrita continua conectada, e é por isso que o portão
	 * precisa existir.
	 *
	 * <p>A resposta carrega o que quem é punido tem direito de saber — tipo da sanção, motivo e,
	 * na suspensão, o prazo de revisão — porque a punição invisível é a que vira ticket de
	 * suporte. O BAN e o PERMABAN não têm prazo: a mensagem é definitiva por natureza.
	 */
	public void requireNotRestricted(String userId) {
		Optional<SanctionGateReader.ActiveSanctionSummary> active = reader.activeSanctionOf(userId);
		if (active.isEmpty()) {
			return;
		}
		SanctionGateReader.ActiveSanctionSummary sanction = active.get();
		StringBuilder message = new StringBuilder("Sua conta está ").append(label(sanction.type()))
			.append(" por moderação");
		if (sanction.reason() != null && !sanction.reason().isBlank()) {
			message.append(". ").append(REASON_LABEL).append(sanction.reason());
		}
		if ("SUSPENDED".equals(sanction.type())) {
			message.append(UNTIL_LABEL).append(sanction.createdAt().plus(suspensionGrace).toString());
		}
		message.append(". Você pode acompanhar e recorrer na página Penalidades.");
		throw new ApiException(403, CODE, message.toString());
	}

	/** Rótulo por tipo, com a restrição como resposta padrão a qualquer tipo novo do enum. */
	private static String label(String type) {
		return switch (type) {
			case "SUSPENDED" -> "suspensa";
			case "BAN", "PERMABAN" -> "banida";
			default -> "restrita";
		};
	}
}
