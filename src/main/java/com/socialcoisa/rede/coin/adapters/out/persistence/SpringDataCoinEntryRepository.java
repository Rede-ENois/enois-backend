package com.socialcoisa.rede.coin.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCoinEntryRepository extends JpaRepository<CoinEntryJpaEntity, String> {

	Optional<CoinEntryJpaEntity> findFirstByAccountIdOrderByCreatedAtDesc(String accountId);

	boolean existsByKindAndRefId(com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind, String refId);

	Optional<CoinEntryJpaEntity> findByAccountIdAndIdempotencyKeyOrderByIdDesc(String accountId, String idempotencyKey);

	List<CoinEntryJpaEntity> findByAccountIdOrderByCreatedAtDesc(String accountId, Pageable pageable);

	Slice<CoinEntryJpaEntity> findByAccountIdOrderByCreatedAtDescIdDesc(String accountId, Pageable pageable);

	Slice<CoinEntryJpaEntity> findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
			String accountId, Instant from, Instant to, Pageable pageable);

	Slice<CoinEntryJpaEntity> findByAccountIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
			String accountId, Instant from, Pageable pageable);

	Slice<CoinEntryJpaEntity> findByAccountIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
			String accountId, Instant to, Pageable pageable);

	@Query("SELECT COALESCE(SUM(-e.amount), 0) FROM CoinEntryJpaEntity e " +
		"WHERE e.accountId = :accountId AND e.amount < 0 AND e.createdAt >= :start AND e.createdAt < :end")
	long sumDebitsBetween(@Param("accountId") String accountId, @Param("start") Instant start,
			@Param("end") Instant end);

	/** O LEDGER é a verdade: a soma dos lançamentos reconstrói o saldo. */
	@Query("SELECT COALESCE(SUM(e.amount), 0) FROM CoinEntryJpaEntity e WHERE e.accountId = :accountId")
	long sumAmounts(@Param("accountId") String accountId);

	java.util.Optional<CoinEntryJpaEntity> findByAccountIdAndRefId(String accountId, String refId);

	java.util.Optional<CoinEntryJpaEntity> findFirstByRefIdAndKindOrderByIdAsc(String refId,
			com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind);

	long countByKindAndRefIdStartingWith(com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind, String refPrefix);
}
