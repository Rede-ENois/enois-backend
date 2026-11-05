package com.socialcoisa.rede.calls.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.calls.application.port.out.CallReportRepository;
import com.socialcoisa.rede.calls.application.port.out.CallRepository;
import com.socialcoisa.rede.calls.domain.CallReportContext;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.moderation.application.ModerationService;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Denúncia de uma chamada, com a fotografia do momento.
 *
 * <p>Quem pode denunciar quem não é decidido pelo cliente: só denuncia quem participa do vínculo
 * da chamada, e só pode apontar alguém que realmente entrou naquela sala. O que fica gravado
 * (quem estava dentro, quem foi acusado, a captura) é montado aqui a partir do banco e da
 * imagem enviada — o corpo do pedido não informa nada disso.</p>
 *
 * <p>A imagem é opcional de propósito. Sem ela a denúncia vale, com o relato; com ela a triagem
 * tem o que olhar quando o relato é sobre o que a tela mostrava. Não existe gravação de áudio nem
 * de vídeo: é uma fotografia, pedida no instante da denúncia.</p>
 */
@Service
public class CallReportService {

	/**
	 * Teto do relato de quem denuncia. A denúncia também recebe quem estava na sala e quem foi
	 * apontado (nomes reais), então sobra menos da coluna de descrição (2000 caracteres).
	 */
	static final int MAX_DESCRIPTION = 1200;

	private final CallRepository calls;
	private final CallReportRepository contexts;
	private final CallAudience audience;
	private final ModerationService moderation;
	private final ValidateMediaOwnershipUseCase mediaOwnership;
	private final ActionThrottle throttle;
	private final UserProfileRepository profiles;
	private final int reportsPerHour;
	private final Clock clock;

	public CallReportService(CallRepository calls, CallReportRepository contexts, CallAudience audience,
			ModerationService moderation, ValidateMediaOwnershipUseCase mediaOwnership, ActionThrottle throttle,
			UserProfileRepository profiles,
			@org.springframework.beans.factory.annotation.Value("${rede.moderation.reports-per-hour:10}") int reportsPerHour,
			Clock clock) {
		this.calls = calls;
		this.contexts = contexts;
		this.audience = audience;
		this.moderation = moderation;
		this.mediaOwnership = mediaOwnership;
		this.throttle = throttle;
		this.profiles = profiles;
		this.reportsPerHour = reportsPerHour;
		this.clock = clock;
	}

	/**
	 * Quem dá para denunciar nesta chamada: as outras pessoas que já entraram na sala, com o nome
	 * que a tela mostra. É o que a pessoa vê ao escolher — e o servidor confere de novo no envio.
	 */
	@Transactional(readOnly = true)
	public List<ParticipantView> reportable(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		List<String> joined = calls.participantsWhoJoined(call.id());
		List<ParticipantView> result = new ArrayList<>();
		for (String userId : joined) {
			if (userId.equals(actorId)) {
				continue;
			}
			result.add(new ParticipantView(userId, displayName(userId)));
		}
		return result;
	}

	/**
	 * Registra a denúncia contra um participante apontado e o contexto do instante. A captura é
	 * um upload já feito pela pessoa (validado como dela) e vira a prova anexada à denúncia.
	 */
	@Transactional
	public Report report(String callId, String actorId, String reportedUserId, ReportReason reason,
			String description, String mediaId) {
		CallSession call = requireCall(callId, actorId);
		if (!StringUtils.hasText(reportedUserId) || reportedUserId.equals(actorId)) {
			throw new ApiException(400, "INVALID_REPORTED_USER", "Escolha a pessoa da chamada que voce quer denunciar.");
		}
		List<String> joined = calls.participantsWhoJoined(call.id());
		if (!joined.contains(reportedUserId)) {
			// Não basta ser do vínculo: precisa ter entrado NAQUELA sala.
			throw new ApiException(400, "NOT_IN_CALL", "Esta pessoa nao entrou nesta chamada.");
		}
		String cleanDescription = description == null ? "" : description.trim();
		if (cleanDescription.length() > MAX_DESCRIPTION) {
			throw new ApiException(400, "DESCRIPTION_TOO_LONG",
				"O relato pode ter no maximo " + MAX_DESCRIPTION + " caracteres.");
		}
		if (reason == null) {
			throw new ApiException(400, "INVALID_REASON", "Motivo da denuncia e obrigatorio.");
		}
		// Denúncia por denúncia: o teto de frequência é o mesmo das outras, e a mesma pessoa não
		// abre dois casos contra o mesmo participante da mesma chamada.
		throttle.exigePermitido("report", actorId, reportsPerHour, java.time.Duration.ofHours(1));
		moderation.ensureCallNotReported(actorId, call.id(), reportedUserId);
		String cleanMedia = StringUtils.hasText(mediaId) ? mediaId.trim() : null;
		if (cleanMedia != null) {
			// A captura precisa ter sido enviada por quem denuncia: não se anexa imagem de terceiro.
			mediaOwnership.validateOwnedBy(new UserId(actorId), List.of(cleanMedia));
		}
		Report report = moderation.reportCall(actorId, call.id(), reportedUserId, reason, cleanDescription,
			joined);
		contexts.save(CallReportContext.of(report.id(), call.id(), reportedUserId, joined, cleanMedia,
			clock.instant()));
		return report;
	}

	private CallSession requireCall(String callId, String actorId) {
		CallSession call = calls.findById(callId)
			.orElseThrow(() -> new ApiException(404, "CALL_NOT_FOUND", "Chamada nao encontrada ou expirada."));
		audience.requireMember(call, actorId);
		return call;
	}

	private String displayName(String userId) {
		return profiles.findById(userId).map(UserProfile::displayName).orElse("Alguem");
	}

	/** Alguém que pode ser denunciado nesta sala. */
	public record ParticipantView(String userId, String displayName) {
	}
}
