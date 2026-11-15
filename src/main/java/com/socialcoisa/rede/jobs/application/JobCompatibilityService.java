package com.socialcoisa.rede.jobs.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.jobs.domain.JobCompatibility;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Compatibilidade entre uma pessoa e uma vaga, para o selo de "match" da listagem.
 *
 * <p>Lê o perfil UMA vez por requisição de listagem (não por linha) e devolve um objeto pronto:
 * o cálculo em si fica no domínio ({@link JobCompatibility}), que não conhece repositório — é
 * função pura e testável sem Spring.</p>
 *
 * <p>Quem não tem perfil (ou tem perfil sem os sinais) recebe um objeto vazio, e o score das
 * vagas é 0: a tela simplesmente não mostra o selo, em vez de fingir compatibilidade.</p>
 */
@Service
public class JobCompatibilityService {

	private final UserProfileRepository profiles;

	public JobCompatibilityService(UserProfileRepository profiles) {
		this.profiles = profiles;
	}

	/** Pré-cálculo para as vagas de uma listagem. `userId` nulo (visitante) devolve o vazio. */
	public JobCompatibility forViewer(String userId) {
		if (userId == null) {
			return JobCompatibility.of(List.of(), null, null);
		}
		return profiles.findById(userId)
			.map(profile -> JobCompatibility.of(profile.skills(),
				profile.place() == null ? null : profile.place().city(),
				profile.place() == null ? null : profile.place().state()))
			.orElseGet(() -> JobCompatibility.of(List.of(), null, null));
	}
}
