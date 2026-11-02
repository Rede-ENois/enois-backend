package com.socialcoisa.rede.coin.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.coin.domain.UserStreak;

public interface UserStreakRepository {

	Optional<UserStreak> find(String userId);

	void save(UserStreak streak);
}
