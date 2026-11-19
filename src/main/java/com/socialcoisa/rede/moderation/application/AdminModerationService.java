package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.application.port.out.UserWarningRepository;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.moderation.domain.UserWarning;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Ações administrativas DIRETAS do painel: advertir, remover publicação e banir sem votação.
 *
 * <p>Existe ao lado do sistema de quórum, não no lugar dele. Moderadores continuam decidindo
 * denúncia por votação ({@link ModeratorService#vote}) e propondo sanção coletiva
 * ({@link SanctionProposalService}); a votação nunca ganha estas rotas, e estas rotas nunca contam
 * voto. A separação é por papel: as rotas deste serviço são de ADMIN, confirmadas pelo
 * {@code AdminGuard} no controller e pelo {@code AdminRouteInterceptor} antes dele.</p>
 *
 * <p>Toda decisão grava uma linha em {@code audit_logs} com o administrador, o alvo, o motivo e o
 * resultado, e notifica o autor. As regras de efeito (revogar sessões, purgar conteúdo no ban, soft
 * delete do post com mídia) são as que a plataforma já usa — aqui não há caminho novo de punição,
 * só a porta administrativa para ele.</p>
 */
@Service
public class AdminModerationService {

	static final String AUDIT_SANCTION = "ADMIN_SANCTION_APPLIED";
	static final String AUDIT_POST_REMOVED = "ADMIN_POST_REMOVED";
	private static final int WARNING_HISTORY = 50;
	private static final int HISTORY_LIMIT = 100;

	private final ModeratorService moderators;
	private final SanctionRepository sanctions;
	private final SanctionService sanctionService;
	private final AuthSessionRepository sessions;
	private final ModerationRemovePostUseCase posts;
	private final UserProfileRepository profiles;
	private final UserWarningRepository warnings;
	private final NotificationService notifications;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration grace;

	public AdminModerationService(ModeratorService moderators, SanctionRepository sanctions,
			SanctionService sanctionService, AuthSessionRepository sessions, ModerationRemovePostUseCase posts,
			UserProfileRepository profiles, UserWarningRepository warnings, NotificationService notifications,
			AuditTrail audit, Clock clock,
			@Value("${rede.moderation.suspension.grace-days:7}") int graceDays) {
		this.moderators = moderators;
		this.sanctions = sanctions;
		this.sanctionService = sanctionService;
		this.sessions = sessions;
		this.posts = posts;
		this.profiles = profiles;
		this.warnings = warnings;
		this.notifications = notifications;
		this.audit = audit;
		this.clock = clock;
		// O prazo da suspensão é o MESMO do worker que a converte em ban (SuspensionExpiryWorker):
		// a tela não pode prometer uma data que o servidor não vai cumprir.
		this.grace = Duration.ofDays(Math.max(1, Math.min(graceDays, 365)));
	}

	/**
	 * Banimento (ou restrição/suspensão) direto por administrador. Reaproveita
	 * {@link ModeratorService#sanctionUser}: a sanção nasce com a referência {@code admin:{id}}, as
	 * sessões caem na hora, o login é recusado enquanto ela valer e o conteúdo entra na purga do ban.
	 *
	 * <p>O que acrescenta: justificativa obrigatória para QUALQUER tipo (inclusive RESTRICTION), para
	 * o histórico nunca ter uma linha sem motivo, e a linha de auditoria que não existia.</p>
	 */
	@Transactional
	public ModeratorService.SanctionResult sanction(String adminId, String username, SanctionType type,
			String reason) {
		if (type == null) {
			throw new ApiException(400, "INVALID_SANCTION_TYPE", "Escolha restrição, banimento ou banimento permanente.");
		}
		String cleanReason = UserWarning.requireReason(reason, "punição");
		ModeratorService.SanctionResult result = moderators.sanctionUser(adminId, username, type, cleanReason);
		audit.record(adminId, AUDIT_SANCTION, result.userId(), "{\"username\":\"" + quote(result.username())
			+ "\",\"type\":\"" + result.type() + "\",\"status\":\"" + result.status() + "\",\"reason\":\""
			+ quote(cleanReason) + "\"}");
		return result;
	}

	/**
	 * Remoção administrativa de publicação, independente de denúncia. O motivo é obrigatório: a
	 * remoção sem justificativa não é reversível por quem lê o histórico.
	 *
	 * <p>Repetir o pedido é seguro: a segunda chamada devolve {@code ALREADY_OFFLINE} sem gravar
	 * segunda linha de auditoria, e id que não existe devolve {@code NOT_FOUND} — nunca "sucesso"
	 * para uma remoção que não aconteceu.</p>
	 */
	@Transactional
	public PostRemoval removePost(String adminId, String postId, String reason) {
		String cleanReason = UserWarning.requireReason(reason, "remoção da publicação");
		if (postId == null || postId.isBlank()) {
			throw new ApiException(400, "INVALID_POST_ID", "Informe a publicação.");
		}

		var outcome = posts.removeAsAdminWithOutcome(adminId, postId.trim(), "ADMIN:" + cleanReason);
		if (outcome == ModerationRemovePostUseCase.RemovalOutcome.REMOVED) {
			// O DeletePostService já grava POST_DELETED_BY_MODERATION; esta linha amarra o motivo
			// ao painel administrativo e ao autor para o histórico por conta.
			audit.record(adminId, AUDIT_POST_REMOVED, postId.trim(), "{\"reason\":\"" + quote(cleanReason) + "\"}");
		}
		return new PostRemoval(postId.trim(), outcome.name());
	}

	public record PostRemoval(String postId, String status) {
	}

	/**
	 * Visão do perfil para o painel: o que já foi decidido sobre a conta (sanção ativa, advertências)
	 * e o histórico de sanções, para o administrador decidir com o contexto à vista.
	 */
	@Transactional(readOnly = true)
	public AdminUserView user(String username) {
		UserProfile target = resolve(username);
		Optional<UserSanction> active = sanctions.findActiveByUser(target.id());
		long activeWarnings = warnings.countActiveByUser(target.id());
		UserWarning warned = null;
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<SanctionRow> history = new ArrayList<>();
		active.ifPresent(sanction -> {
			seen.add(sanction.id());
			history.add(SanctionRow.from(sanction, grace));
		});
		for (UserSanction sanction : sanctionService.sanctionsOf(target.id())) {
			if (seen.add(sanction.id())) {
				history.add(SanctionRow.from(sanction, grace));
			}
		}
		for (UserWarning warning : warnings.findByUser(target.id(), WARNING_HISTORY)) {
			if (warning.isActive()) {
				warned = warning;
				break;
			}
		}
		SanctionRow activeRow = active.isEmpty() ? null : history.getFirst();
		return new AdminUserView(target.id(), target.username(), target.name(), activeRow, activeWarnings,
			warned == null ? null : WarningRow.from(warned));
	}

	public record AdminUserView(String userId, String username, String name, SanctionRow activeSanction,
			long activeWarnings, WarningRow latestActiveWarning) {
	}

	/** A última advertência ativa, como a ficha do perfil mostra antes de decidir. */
	public record WarningRow(String id, String reason, String createdAt, String adminId) {
		static WarningRow from(UserWarning warning) {
			return new WarningRow(warning.id(), warning.reason(), warning.createdAt().toString(), warning.adminId());
		}
	}

	/** Histórico administrativo do perfil: o que já foi decidido sobre a conta, mais recente primeiro. */
	@Transactional(readOnly = true)
	public AdminHistoryView history(String username, int limit) {
		UserProfile target = resolve(username);
		int size = Math.max(1, Math.min(limit, HISTORY_LIMIT));
		List<HistoryRow> rows = new ArrayList<>();
		for (UserSanction sanction : sanctionService.sanctionsOf(target.id())) {
			rows.add(HistoryRow.from(sanction, grace));
		}
		for (UserWarning warning : warnings.findByUser(target.id(), size)) {
			rows.add(HistoryRow.from(warning));
		}
		// Junta as duas fontes e só então corta: a tela mostra o que é mais recente, não o que
		// veio da tabela que foi consultada primeiro.
		rows.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
		return new AdminHistoryView(target.id(), target.username(), rows.size() > size ? rows.subList(0, size) : rows);
	}

	public record AdminHistoryView(String userId, String username, List<HistoryRow> rows) {
	}

	/**
	 * Uma linha do histórico administrativo. {@code kind} diz o que foi ({@code SANCTION} ou
	 * {@code WARNING}); {@code active} diz se ainda morde; {@code revokedAt} guarda a revisão.
	 */
	public record HistoryRow(String id, String kind, String type, String reason, String status, String createdAt,
			boolean active, String revokedAt, String revokedBy, String dueAt) {
		static HistoryRow from(UserSanction sanction, Duration grace) {
			SanctionRow row = SanctionRow.from(sanction, grace);
			return new HistoryRow(sanction.id(), "SANCTION", sanction.type().name(), sanction.reason(),
				sanction.status().name(), sanction.createdAt().toString(), sanction.isActive(),
				sanction.removedAt() == null ? null : sanction.removedAt().toString(), sanction.removedBy(), row.dueAt());
		}

		static HistoryRow from(UserWarning warning) {
			return new HistoryRow(warning.id(), "WARNING", "WARNING", warning.reason(),
				warning.isActive() ? "ACTIVE" : "REVOKED", warning.createdAt().toString(), warning.isActive(),
				warning.revokedAt() == null ? null : warning.revokedAt().toString(), warning.revokedBy(), null);
		}
	}

	/** Uma sanção como a tela mostra: tipo, motivo, status e, na suspensão, o prazo que resta. */
	public record SanctionRow(String id, String type, String reason, String status, String createdAt,
			String dueAt, boolean active, String liftedBy) {
		static SanctionRow from(UserSanction sanction, Duration grace) {
			String dueAt = null;
			if (sanction.isActive() && sanction.type() == SanctionType.SUSPENDED) {
				dueAt = sanction.createdAt().plus(grace).toString();
			}
			return new SanctionRow(sanction.id(), sanction.type().name(), sanction.reason(), sanction.status().name(),
				sanction.createdAt().toString(), dueAt, sanction.isActive(), sanction.removedBy());
		}
	}

	/**
	 * Resolve o @ como o resto do painel: o slug é armazenado em minúsculas, e quem digita o @ em
	 * maiúscula (ou cola de outro lugar) continua encontrando a conta em vez de receber 404.
	 */
	private UserProfile resolve(String username) {
		String typed = username == null ? "" : username.trim();
		Optional<UserProfile> found = typed.isEmpty() ? Optional.empty() : profiles.findByUsername(typed);
		if (found.isEmpty() && !typed.isEmpty()) {
			found = profiles.findByUsername(typed.toLowerCase(java.util.Locale.ROOT));
		}
		return found.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
	}

	private static String quote(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}