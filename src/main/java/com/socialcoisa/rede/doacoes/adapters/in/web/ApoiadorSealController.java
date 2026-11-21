package com.socialcoisa.rede.doacoes.adapters.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O selo de apoiador no perfil público: só o booleano. Valor total e data de cada doação são
 * dado privado de quem doou — o perfil sabe se doou, nunca quanto.
 */
@RestController
class ApoiadorSealController {

	private final DoacaoRepository doacoes;
	private final UserProfileRepository profiles;
	private final SanctionGateReader sanctionGate;

	ApoiadorSealController(DoacaoRepository doacoes, UserProfileRepository profiles, SanctionGateReader sanctionGate) {
		this.doacoes = doacoes;
		this.profiles = profiles;
		this.sanctionGate = sanctionGate;
	}

	@GetMapping("/users/{username}/apoiador")
	ApoiadorSealView byUsername(@PathVariable String username) {
		UserProfile profile = profiles.findByUsername(username)
			.filter(p -> !sanctionGate.banned(p.id()))
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		return new ApoiadorSealView(doacoes.confirmedUserIds(java.util.Set.of(profile.id())).contains(profile.id()));
	}

	record ApoiadorSealView(boolean apoiador) {
	}
}
