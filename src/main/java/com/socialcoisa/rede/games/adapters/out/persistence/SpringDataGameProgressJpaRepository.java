package com.socialcoisa.rede.games.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGameProgressJpaRepository extends JpaRepository<GameProgressJpaEntity, String> {

	Optional<GameProgressJpaEntity> findByGameIdAndUserId(String gameId, String userId);
}
