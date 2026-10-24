package com.socialcoisa.rede.coin.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinAccount;
import com.socialcoisa.rede.coin.domain.CoinEntry;

@Repository
class CoinLedgerPersistenceAdapter implements CoinLedgerRepository {

	private final SpringDataCoinAccountRepository accounts;
	private final SpringDataCoinEntryRepository entries;

	CoinLedgerPersistenceAdapter(SpringDataCoinAccountRepository accounts, SpringDataCoinEntryRepository entries) {
		this.accounts = accounts;
		this.entries = entries;
	}

	@Override
	public Optional<CoinAccount> findAccount(String accountId) {
		return accounts.findById(accountId).map(CoinAccountJpaEntity::toDomain);
	}

	@Override
	public CoinAccount insertAccount(CoinAccount account) {
		return accounts.save(CoinAccountJpaEntity.fromDomain(account)).toDomain();
	}

	@Override
	public CoinAccount updateBalance(String accountId, long newBalance, long expectedVersion, long nextVersion) {
		// Update otimista: se outra transação mexeu no snapshot entre leitura e escrita,
		// ninguém atualiza — o chamador refaz (409 COIN_CONCURRENCY para o usuário).
		int updated = accounts.updateBalanceIfVersion(accountId, newBalance, expectedVersion, nextVersion);
		if (updated == 0) {
			return null;
		}
		return accounts.findById(accountId).map(CoinAccountJpaEntity::toDomain).orElse(null);
	}

	@Override
	public Optional<CoinEntry> lastEntry(String accountId) {
		return entries.findFirstByAccountIdOrderByCreatedAtDesc(accountId).map(CoinEntryJpaEntity::toDomain);
	}

	@Override
	public CoinEntry insertEntry(CoinEntry entry) {
		try {
			return entries.save(CoinEntryJpaEntity.fromDomain(entry)).toDomain();
		}
		catch (DataIntegrityViolationException duplicate) {
			// A mesma operação com a mesma chave já lançou: devolve a existente (idempotência).
			return entries
				.findByAccountIdAndIdempotencyKeyOrderByIdDesc(entry.accountId(), entry.idempotencyKey())
				.map(CoinEntryJpaEntity::toDomain)
				.orElseThrow(() -> duplicate);
		}
	}

	@Override
	public boolean existsByKindAndRefId(com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind, String refId) {
		return entries.existsByKindAndRefId(kind, refId);
	}

	@Override
	public List<CoinEntry> recentEntries(String accountId, int limit) {
		return entries.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(CoinEntryJpaEntity::toDomain).toList();
	}

	@Override
	public long transferredOn(String accountId, Instant start, Instant end) {
		return entries.sumDebitsBetween(accountId, start, end);
	}

	@Override
	public boolean accountExistsForUser(String userId) {
		return accounts.existsById(userId);
	}

	@Override
	public java.util.Optional<CoinEntry> findByAccountIdAndRefId(String accountId, String refId) {
		return entries.findByAccountIdAndRefId(accountId, refId).map(CoinEntryJpaEntity::toDomain);
	}

	@Override
	public java.util.Optional<CoinEntry> findByRefIdAndKind(String refId, com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind) {
		return entries.findFirstByRefIdAndKindOrderByIdAsc(refId, kind).map(CoinEntryJpaEntity::toDomain);
	}

	@Override
	public java.util.List<CoinAccount> allAccounts() {
		return accounts.findAll().stream().map(CoinAccountJpaEntity::toDomain).toList();
	}

	@Override
	public long balanceFromLedger(String accountId) {
		return entries.sumAmounts(accountId);
	}

	@Override
	public long totalBalance() {
		return accounts.sumBalances();
	}

	@Override
	public long countRewardsByRefPrefix(String prefix) {
		return entries.countByKindAndRefIdStartingWith(com.socialcoisa.rede.coin.domain.CoinEntry.Kind.REWARD, prefix);
	}
}
