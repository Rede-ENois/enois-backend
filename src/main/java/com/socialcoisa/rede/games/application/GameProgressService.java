package com.socialcoisa.rede.games.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.games.application.port.out.GameProgressRepository;
import com.socialcoisa.rede.games.domain.GameProgress;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O progresso dos jogos vinculado à conta: trocar de aparelho ou limpar dados não perde o
 * histórico (relato do suporte que originou o épico). O estado é OPACO — o backend guarda o
 * que o jogo manda e devolve o que há; interpretação é do jogo. "Último estado vence": o PUT
 * substitui o estado inteiro, e o jogo que quiser mesclar mescla no cliente antes de mandar.
 */
@Service
public class GameProgressService {

	/** Teto do estado: o progresso de um jogo não é um backup de dados arbitrários. */
	private static final int MAX_STATE_LENGTH = 32_000;

	private final GameProgressRepository repository;
	private final Clock clock;

	public GameProgressService(GameProgressRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Optional<GameProgress> find(String gameId, String userId) {
		return repository.find(gameId, userId);
	}

	/**
	 * Salva o estado inteiro (último estado vence). Um jogo sem estado algum no servidor ganha
	 * o primeiro; o estado vazio recusa — gravar nada não é gravar progresso.
	 */
	@Transactional
	public GameProgress save(String gameId, String userId, String state) {
		if (!StringUtils.hasText(state)) {
			throw new ApiException(400, "PROGRESSO_VAZIO", "O estado do progresso está vazio.");
		}
		if (state.length() > MAX_STATE_LENGTH) {
			throw new ApiException(400, "PROGRESSO_GRANDE", "O estado do progresso passou do limite.");
		}
		GameProgress existente = repository.find(gameId, userId).orElse(null);
		Instant agora = clock.instant();
		if (existente == null) {
			String id = "gprog_" + UUID.randomUUID().toString().replace("-", "");
			return repository.save(GameProgress.start(id, gameId, userId, state, agora));
		}
		return repository.save(existente.withState(state, agora));
	}
}
