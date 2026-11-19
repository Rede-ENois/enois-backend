package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.moderation.application.port.out.UserWarningRepository;
import com.socialcoisa.rede.moderation.domain.UserWarning;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Advertência administrativa direta: um administrador avisa uma conta sem denúncia e sem votação.
 *
 * <p>O caminho de votação continua existindo e não passa por aqui — moderadores decidem denúncia
 * pelo painel de votos, e esse fluxo não mudou. O que esta classe acrescenta é a decisão individual
 * do administrador sobre o PERFIL: o aviso fica guardado na conta, é notificado uma vez e aparece no
 * histórico dela mesmo depois de revogado.</p>
 *
 * <p>A autorização é responsabilidade do chamador (a rota é restrita ao admin pelo interceptor e
 * confirma com o {@code AdminGuard} antes de chamar); aqui fica a regra de negócio: motivo mínimo,
 * não advertir a si mesma, idempotência pela chave do envio e uma linha de auditoria por decisão.</p>
 */
@Service
public class UserWarningService {

	static final String AUDIT_ISSUED = "ADMIN_WARNING_ISSUED";
	static final String AUDIT_REVOKED = "ADMIN_WARNING_REVOKED";

	private final UserWarningRepository warnings;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;
	private final AuditTrail audit;
	private final Clock clock;

	public UserWarningService(UserWarningRepository warnings, UserProfileRepository profiles,
			NotificationService notifications, AuditTrail audit, Clock clock) {
		this.warnings = warnings;
		this.profiles = profiles;
		this.notifications = notifications;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Adverte a conta. {@code idempotencyToken} vem da chave do envio: o mesmo administrador repetindo
	 * o MESMO pedido (duplo clique, retentativa de rede) recebe o aviso já criado em vez de um
	 * segundo registro — advertência é histórico, e histórico duplicado mente sobre a reincidência.
	 */
	@Transactional
	public WarningView warn(String adminId, String username, String reason, String idempotencyToken) {
		String cleanReason = UserWarning.requireReason(reason, "advertência");
		UserProfile target = resolve(username);
		if (target.id().equals(adminId)) {
			throw new ApiException(400, "CANNOT_WARN_SELF", "Você não pode advertir a própria conta.");
		}
		String id = idFor(idempotencyToken);
		UserWarning existing = warnings.findById(id).orElse(null);
		if (existing != null) {
			if (!existing.adminId().equals(adminId) || !existing.userId().equals(target.id())) {
				throw new ApiException(409, "DUPLICATE_WARNING", "Este pedido já foi usado em outra advertência.");
			}
			return view(existing, activeWarnings(existing.userId()));
		}
		UserWarning warning = warnings.save(
			UserWarning.create(id, target.id(), adminId, cleanReason, Instant.now(clock)));
		notifications.create(target.id(), "MODERACAO", adminId, "Advertência da administração",
			"Um administrador registrou uma advertência na sua conta: " + cleanReason
				+ ". O histórico fica em penalidades. Revise as diretrizes da rede para não perder o acesso.",
			"/penalidades", "Ver histórico");
		audit.record(adminId, AUDIT_ISSUED, target.id(), metadata(warning.id(), target.id(), cleanReason));
		return view(warning, activeWarnings(target.id()));
	}

	/** Histórico do perfil (admin): ativas e revogadas, mais recente primeiro. */
	@Transactional(readOnly = true)
	public List<WarningView> history(String username, int limit) {
		UserProfile target = resolve(username);
		List<UserWarning> rows = warnings.findByUser(target.id(), limit);
		Map<String, ProfileSummary> admins = adminsById(rows.stream()
			.map(UserWarning::adminId).distinct().toList());
		return rows.stream()
			.map(warning -> view(warning, admins.get(warning.adminId())))
			.toList();
	}

	/** Revoga mantendo a linha: a advertência sai do que conta para a escalada, não do histórico. */
	@Transactional
	public WarningView revoke(String adminId, String warningId, String reason) {
		String cleanReason = UserWarning.requireReason(reason, "revogação da advertência");
		UserWarning warning = warnings.findById(warningId)
			.orElseThrow(() -> new ApiException(404, "WARNING_NOT_FOUND", "Advertência não encontrada."));
		UserWarning revoked = warnings.save(warning.revoke(adminId, Instant.now(clock)));
		notifications.create(revoked.userId(), "MODERACAO", adminId, "Advertência revogada",
			"Uma pessoa do time revisou uma advertência da sua conta e ela foi revogada. Motivo: " + cleanReason,
			"/penalidades", "Ver histórico");
		audit.record(adminId, AUDIT_REVOKED, revoked.userId(),
			metadata(revoked.id(), revoked.userId(), cleanReason));
		return view(revoked, activeWarnings(revoked.userId()));
	}

	/** Quantas advertências ativas a conta acumulou (ficha do perfil no painel). */
	@Transactional(readOnly = true)
	public long activeWarnings(String userId) {
		return warnings.countActiveByUser(userId);
	}

	/** O @ em minúsculas é a chave da conta: quem digita em maiúscula continua encontrando. */
	private UserProfile resolve(String username) {
		String typed = username == null ? "" : username.trim();
		java.util.Optional<UserProfile> found = typed.isEmpty()
			? java.util.Optional.empty() : profiles.findByUsername(typed);
		if (found.isEmpty() && !typed.isEmpty()) {
			found = profiles.findByUsername(typed.toLowerCase(java.util.Locale.ROOT));
		}
		return found.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
	}

	/**
	 * O id nasce da chave do envio quando ela existe (o mesmo pedido cai no mesmo registro); sem
	 * chave é um id novo — quem não mandou chave não pediu idempotência.
	 */
	private static String idFor(String idempotencyToken) {
		if (idempotencyToken == null || idempotencyToken.isBlank()) {
			return "warn_" + UUID.randomUUID().toString().replace("-", "");
		}
		String digest = com.socialcoisa.rede.shared.security.Hashes.sha256Hex("warning:" + idempotencyToken.trim());
		return "warn_" + digest.substring(0, 32);
	}

	private static String metadata(String warningId, String userId, String reason) {
		return "{\"warningId\":\"" + warningId + "\",\"userId\":\"" + userId + "\",\"reason\":\""
			+ reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}

	private Map<String, ProfileSummary> adminsById(List<String> adminIds) {
		Map<String, ProfileSummary> result = new HashMap<>();
		for (UserProfile profile : profiles.findAllByIds(adminIds)) {
			result.put(profile.id(), new ProfileSummary(profile.username(), profile.name()));
		}
		return result;
	}

	private WarningView view(UserWarning warning, long activeCount) {
		return view(warning, activeCount, null);
	}

	private WarningView view(UserWarning warning, ProfileSummary admin) {
		return view(warning, activeWarnings(warning.userId()), admin);
	}

	private static WarningView view(UserWarning warning, long activeCount, ProfileSummary admin) {
		return new WarningView(warning.id(), warning.userId(), warning.adminId(),
			admin == null ? null : admin.username(), admin == null ? null : admin.name(), warning.reason(),
			warning.createdAt().toString(), warning.isActive(), activeCount,
			warning.revokedAt() == null ? null : warning.revokedAt().toString(), warning.revokedBy());
	}

	/** Nome de quem advertiu, para o histórico mostrar a pessoa e não um id. */
	private record ProfileSummary(String username, String name) {
	}

	public record WarningView(String id, String userId, String adminId, String adminUsername, String adminName,
			String reason, String createdAt, boolean active, long activeCount, String revokedAt, String revokedBy) {
	}
}