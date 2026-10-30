package com.socialcoisa.rede.network.application;

import java.time.Clock;
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

	@Transactional
	public void mute(String muterId, String targetUserId) {
		if (muterId.equals(targetUserId)) {
			throw new ApiException(400, "SELF_MUTE", "Nao e possivel silenciar a si mesmo.");
		}
		if (userProfileRepository.findById(targetUserId).isEmpty()) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		muteRepository.save(UserMute.create(muterId, targetUserId, clock.instant()));
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

	public Set<String> mutedIdsFor(String muterId) {
		if (muterId == null || muterId.isBlank()) {
			return Set.of();
		}
		return muteRepository.findMutedIds(muterId);
	}
}
