package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.UserMuteRepository;
import com.socialcoisa.rede.network.domain.UserMute;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Silenciamento: o usuario silenciado desaparece do feed, mensagens e notificacoes de quem
 * silenciou, mas continua vendo normalmente — sem saber que foi silenciado.
 *
 * <p>O silenciamento pode ter prazo: passado o instante, ele deixa de valer sozinho. Quem le
 * ({@link #isMuted}, {@link #mutedIdsFor}, {@link #listMuted}) nunca ve um prazo vencido — o
 * repositorio ja filtra pelo `Clock` da aplicacao, entao nada aqui precisa comparar instantes de
 * novo.</p>
 */
@Service
public class MuteService {

	private final UserMuteRepository muteRepository;
	private final UserProfileRepository userProfileRepository;
	private final Clock clock;

	public MuteService(UserMuteRepository muteRepository, UserProfileRepository userProfileRepository,
			Clock clock) {
		this.muteRepository = muteRepository;
		this.userProfileRepository = userProfileRepository;
		this.clock = clock;
	}

	/** Silenciamento sem prazo: vale ate reativar. */
	@Transactional
	public void mute(String muterId, String targetUserId) {
		mute(muterId, targetUserId, null);
	}

	/**
	 * Silencia alguem por um periodo. `duration` nulo vira "ate reativar"; silenciar de novo a
	 * mesma pessoa renova o prazo a partir de agora.
	 */
	@Transactional
	public void mute(String muterId, String targetUserId, Duration duration) {
		if (muterId.equals(targetUserId)) {
			throw new ApiException(400, "SELF_MUTE", "Nao e possivel silenciar a si mesmo.");
		}
		if (userProfileRepository.findById(targetUserId).isEmpty()) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		muteRepository.save(UserMute.create(muterId, targetUserId, clock.instant(), duration));
	}

	@Transactional
	public void unmute(String muterId, String targetUserId) {
		muteRepository.delete(muterId, targetUserId);
	}

	public List<UserMute> listMuted(String muterId) {
		return muteRepository.findByMuterId(muterId);
	}

	public boolean isMuted(String muterId, String targetUserId) {
		return muteRepository.exists(muterId, targetUserId);
	}

	/** O silenciamento ativo entre os dois (com prazo, se houver) — a tela mostra quando ele vence. */
	public java.util.Optional<UserMute> activeMute(String muterId, String targetUserId) {
		return muteRepository.findActive(muterId, targetUserId);
	}

	public Set<String> mutedIdsFor(String muterId) {
		if (muterId == null || muterId.isBlank()) {
			return Set.of();
		}
		return muteRepository.findMutedIds(muterId);
	}
}
