package com.socialcoisa.rede.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class EmissorSessaoService {
	private final AuthSessionRepository sessoes;
	private final CredentialRepository credenciais;
	private final SanctionGateReader sancoes;
	private final Clock relogio;
	private final Duration validade;
	private final Duration validadeRenovacao;
	private final TwoFactorService segundoFator;
	private final SecureRandom aleatorio = new SecureRandom();

	public EmissorSessaoService(AuthSessionRepository sessoes, CredentialRepository credenciais,
			SanctionGateReader sancoes, Clock relogio, TwoFactorService segundoFator,
			@Value("${rede.auth.access-token-ttl-minutes:60}") int minutos,
			@Value("${rede.auth.refresh-token-ttl-days:30}") int dias) {
		this.sessoes = sessoes;
		this.credenciais = credenciais;
		this.sancoes = sancoes;
		this.relogio = relogio;
		this.validade = Duration.ofMinutes(minutos);
		this.validadeRenovacao = Duration.ofDays(dias);
		this.segundoFator = segundoFator;
	}

	/** O fator local também vale para provedor e confirmação de e-mail de conta existente. */
	public AuthResponse emitir(UserProfile perfil, String ip) {
		var metodo = segundoFator.requiredMethod(perfil.id());
		if (metodo.isPresent()) {
			return AuthResponse.aguardaSegundoFator(segundoFator.startChallenge(perfil.id(), metodo.get()));
		}
		return emitirAposFatores(perfil, ip);
	}

	public AuthResponse emitirAposFatores(UserProfile perfil, String ip) {
		if (!perfil.identityVerified()) {
			throw new ApiException(403, "EMAIL_NOT_VERIFIED", "Confirme sua identidade para entrar.");
		}
		boolean restrita = sancoes.suspended(perfil.id());
		if (sancoes.banned(perfil.id()) && !restrita) {
			throw new ApiException(403, "ACCOUNT_BANNED", "Esta conta foi banida. Fale com o suporte se precisar.");
		}
		var agora = relogio.instant();
		var expira = agora.plus(validade);
		String token = novoToken();
		String renovacao = restrita ? null : novoToken();
		var expiraRenovacao = restrita ? null : agora.plus(validadeRenovacao);
		if (restrita) {
			sessoes.saveSanctionsOnly(token, perfil.id(), agora, expira, ip);
		}
		else {
			sessoes.save(token, renovacao, perfil.id(), agora, expira, expiraRenovacao, ip);
		}
		boolean temSenha = credenciais.findByUserId(perfil.id())
			.map(com.socialcoisa.rede.identity.application.port.out.StoredCredential::passwordUsable).orElse(false);
		return new AuthResponse(token, renovacao, expira, expiraRenovacao, AuthUserView.from(perfil, temSenha),
			restrita ? "SANCTIONS_ONLY" : "FULL");
	}

	private String novoToken() {
		byte[] bytes = new byte[32];
		aleatorio.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
