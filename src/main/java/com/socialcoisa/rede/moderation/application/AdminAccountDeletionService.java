package com.socialcoisa.rede.moderation.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.AuthService;
import com.socialcoisa.rede.moderation.domain.UserWarning;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Exclusão de conta pela ADMINISTRAÇÃO da plataforma — sem a senha da pessoa, que é o que o
 * caminho do próprio dono exige ({@code POST /auth/account/delete}).
 *
 * <p><b>Um caminho só para apagar:</b> a rotina é a mesma de sempre
 * ({@link AuthService#anonymizeAccount}): sessões revogadas, credencial e identidades externas
 * fora, perfil anonimizado e consentimento do banco de talentos apagado. Aqui muda apenas quem
 * decide; a conta não passa a ter duas formas de sumir — o que produziria linhas órfãs em um dos
 * caminhos.</p>
 *
 * <p><b>Proteções:</b> ninguém apaga a si mesmo (o admin perderia a conta que está usando) e
 * nenhuma conta administradora é apagada por esta rota — a superfície administrativa não derruba
 * a própria administração nem a trilha de quem age.</p>
 */
@Service
public class AdminAccountDeletionService {

	private final UserProfileRepository profiles;
	private final AuthService accounts;
	private final AuditTrail audit;

	AdminAccountDeletionService(UserProfileRepository profiles, AuthService accounts, AuditTrail audit) {
		this.profiles = profiles;
		this.accounts = accounts;
		this.audit = audit;
	}

	/** @return {@code DELETED} quando a conta sai agora.   */
	@Transactional
	public Deletion deleteByAdmin(String adminId, String username, String reason) {
		String cleanReason = UserWarning.requireReason(reason, "exclusão da conta");
		UserProfile target = profiles.findByUsername(username == null ? "" : username.trim())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		if (target.id().equals(adminId)) {
			throw new ApiException(400, "CANNOT_DELETE_SELF",
				"A própria conta não é excluída por aqui; peça a outra pessoa da administração.");
		}
		if (target.admin()) {
			throw new ApiException(409, "CANNOT_DELETE_ADMIN",
				"Contas de administração não são excluídas por esta rota.");
		}
		accounts.anonymizeAccount(target.id());
		audit.record(adminId, "ADMIN_ACCOUNT_DELETED", target.id(),
			"{\"username\":\"" + escape(target.username()) + "\",\"reason\":\"" + escape(cleanReason) + "\"}");
		return new Deletion(target.id(), target.username(), "DELETED");
	}

	public record Deletion(String userId, String username, String outcome) {
	}

	private static String escape(String value) {
		return value == null ? ""
			: value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
	}
}