package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedConta;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedContasRepository;

/**
 * Lê as contas elegíveis e a credibilidade pelo módulo DONO ({@code profile}): o wrapped não
 * alcança a tabela {@code users} por conta própria, segue a porta do perfil. A credibilidade é a
 * coluna materializada pelo job do ranking — nunca recalculada aqui.
 */
@Repository
class WrappedContasProfileAdapter implements WrappedContasRepository {

	private final UserProfileRepository profiles;

	WrappedContasProfileAdapter(UserProfileRepository profiles) {
		this.profiles = profiles;
	}

	@Override
	public List<WrappedConta> pagina(String depoisDeUserId, int limite) {
		return profiles.wrappedAccounts(depoisDeUserId, limite).stream()
				.map(a -> new WrappedConta(a.userId(), a.reputation(), a.createdAt()))
				.toList();
	}

	@Override
	public Integer credibilidade(String userId) {
		return profiles.findById(userId).map(p -> p.reputation()).orElse(null);
	}
}
