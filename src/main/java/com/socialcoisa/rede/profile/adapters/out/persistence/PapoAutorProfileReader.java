package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.paporeto.application.port.out.PapoAutorReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

/**
 * A autoria das publicações relacionadas do Papo Reto, lida do acervo de perfis.
 *
 * Vive no pacote do perfil porque é ele quem lê contas; o Papo Reto só conhece a porta. O
 * banido continua com a publicação pública, mas o nome some da tela — a listagem mostra
 * "Usuário" no lugar, como o resto do feed.
 */
@Component
@Transactional(readOnly = true)
class PapoAutorProfileReader implements PapoAutorReader {

	private final UserProfileRepository perfis;

	private final SanctionGateReader sancoes;

	PapoAutorProfileReader(UserProfileRepository perfis, SanctionGateReader sancoes) {
		this.perfis = perfis;
		this.sancoes = sancoes;
	}

	@Override
	public Map<String, Autor> autores(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		Map<String, Autor> autores = new HashMap<>();
		for (UserProfile perfil : perfis.findAllByIds(ids)) {
			autores.put(perfil.id(), new Autor(perfil.displayWithNickname(), perfil.username()));
		}
		return autores;
	}

	@Override
	public Set<String> banidos(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return Set.of();
		}
		return sancoes.bannedAmong(ids);
	}

}
