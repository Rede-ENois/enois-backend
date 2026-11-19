package com.socialcoisa.rede.games.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.games.domain.GameProgress;

public interface GameProgressRepository {

	GameProgress save(GameProgress progress);

	Optional<GameProgress> find(String gameId, String userId);
}
