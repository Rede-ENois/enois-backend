package com.socialcoisa.rede.coin.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCoinAccountRepository extends JpaRepository<CoinAccountJpaEntity, String> {

	@org.springframework.data.jpa.repository.Query("select coalesce(sum(a.balance), 0) from CoinAccountJpaEntity a")
	long sumBalances();

	java.util.List<CoinAccountJpaEntity> findAll();

	/** Scalar row (not the managed entity, which may hold an older balance) under a row lock. */
	@org.springframework.data.jpa.repository.Query(value = "select account_id, balance, version, created_at"
		+ " from coin_accounts where account_id = :id for update", nativeQuery = true)
	java.util.List<Object[]> lockById(@org.springframework.data.repository.query.Param("id") String id);

	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
	@org.springframework.data.jpa.repository.Query("UPDATE CoinAccountJpaEntity a SET a.balance = :newBalance, " +
		"a.version = a.version + 1 WHERE a.accountId = :id AND a.version = :expectedVersion")
	int updateBalanceIfVersion(@org.springframework.data.repository.query.Param("id") String id,
		@org.springframework.data.repository.query.Param("newBalance") long newBalance,
		@org.springframework.data.repository.query.Param("expectedVersion") long expectedVersion,
		@org.springframework.data.repository.query.Param("nextVersion") long nextVersion);

}
