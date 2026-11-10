package com.socialcoisa.rede.identity.adapters.out.provider;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.identity.application.DiditConfig;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationProvider;
import com.socialcoisa.rede.identity.domain.DiditStatus;
import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Didit como provedor de verificação de identidade.
 *
 * <p>Entra no módulo de identidade que já existe: o estado continua na máquina de estados de
 * {@code identity_verifications}, e VERIFIED só é alcançado por leitura confirmada no servidor da
 * Didit. Não existe caminho em que o navegador diga "aprovado".</p>
 *
 * <p>É o único provedor que exige liberação progressiva ({@link #requiresRelease()}), e a
 * checagem fica no serviço de aplicação, antes de qualquer chamada externa.</p>
 */
@Component
public class DiditIdentityProvider implements IdentityVerificationProvider {

	static final String NAME = "didit";

	private final DiditConfig config;
	private final DiditApi api;

	DiditIdentityProvider(DiditConfig config, DiditApi api) {
		this.config = config;
		this.api = api;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean available() {
		return config.configured();
	}

	@Override
	public boolean requiresRelease() {
		return true;
	}

	@Override
	public Start start(String userId, String consentVersion) {
		config.requireNoUnexpectedCharge();
		DiditApi.CreatedSession session = api.createSession(userId)
			.orElseThrow(() -> new ApiException(502, "IDENTITY_PROVIDER_ERROR",
				"Nao foi possivel iniciar a verificacao agora. Tente novamente mais tarde."));
		// O id da sessão da Didit É o identificador da tentativa: é por ele que o webhook e a
		// consulta de desfecho localizam a linha.
		return new Start(session.sessionId(), session.url());
	}

	@Override
	public Optional<Outcome> fetchOutcome(String attemptId) {
		Optional<String> status = api.fetchStatus(attemptId);
		if (status.isEmpty()) {
			return Optional.empty();
		}
		return switch (DiditStatus.of(status.get())) {
			// Só o "Approved" da Didit vira VERIFIED.
			case APPROVED -> Optional.of(new Outcome(IdentityVerificationStatus.VERIFIED, null));
			case DECLINED -> Optional.of(new Outcome(IdentityVerificationStatus.REJECTED, "DIDIT_DECLINED"));
			case EXPIRED -> Optional.of(new Outcome(IdentityVerificationStatus.EXPIRED, "DIDIT_EXPIRED"));
			case ABANDONED -> Optional.of(new Outcome(IdentityVerificationStatus.EXPIRED, "DIDIT_ABANDONED"));
			// Em análise manual, em andamento ou status desconhecido: nada muda. Devolver vazio
			// mantém a tentativa em PENDING, que é o estado honesto.
			case IN_REVIEW, IN_PROGRESS, PENDING, UNKNOWN -> Optional.empty();
		};
	}
}