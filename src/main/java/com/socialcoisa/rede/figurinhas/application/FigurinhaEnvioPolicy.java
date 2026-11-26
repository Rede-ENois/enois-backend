package com.socialcoisa.rede.figurinhas.application;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.identity.application.IdentityVerificationService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Quem pode enviar figurinha para a comunidade: conta com identidade verificada e pelo menos 100
 * de reputação. O envio de figurinha é o que mais circula fora do perfil (a figurinha roda em
 * qualquer conversa), então o piso é alto de propósito.
 *
 * <p>A verificação de identidade não é um requisito separado: a reputação só é contada para quem a
 * verificou (o resto do app também mostra 0 nesse caso). Por isso a recusa vem com código próprio —
 * quem não verificou precisa ver "verifique sua identidade", e não "sua reputação é 0".</p>
 *
 * <p>A decisão também é exposta ({@link #avaliar}) para a tela mostrar o que falta antes de a pessoa
 * escolher o arquivo, em vez de só encontrar a recusa no fim do envio.</p>
 */
@Service
public class FigurinhaEnvioPolicy {

	public static final int REPUTACAO_MINIMA = 100;

	private final ReputationService reputations;
	private final IdentityVerificationService identity;

	public FigurinhaEnvioPolicy(ReputationService reputations, IdentityVerificationService identity) {
		this.reputations = reputations;
		this.identity = identity;
	}

	public Decisao avaliar(String autorId) {
		if (!identity.isVerified(autorId)) {
			return new Decisao(false, 0, REPUTACAO_MINIMA, false, "IDENTITY_REQUIRED");
		}
		int reputacao = reputations.reputationOf(autorId);
		boolean pode = reputacao >= REPUTACAO_MINIMA;
		return new Decisao(pode, reputacao, REPUTACAO_MINIMA, true, pode ? null : "LOW_REPUTATION");
	}

	/** A mesma decisão, em forma de exceção para os passos que gravam. */
	public Decisao exigir(String autorId) {
		Decisao decisao = avaliar(autorId);
		if (decisao.pode()) {
			return decisao;
		}
		if (!decisao.identidadeVerificada()) {
			throw new ReputacaoIndisponivelException(REPUTACAO_MINIMA);
		}
		throw new ApiException(403, "LOW_REPUTATION",
			"Para enviar figurinhas você precisa de pelo menos " + REPUTACAO_MINIMA
				+ " de reputação. Você tem " + decisao.reputacao() + ".");
	}

	public record Decisao(boolean pode, int reputacao, int reputacaoMinima, boolean identidadeVerificada,
			String motivo) {
	}
}
