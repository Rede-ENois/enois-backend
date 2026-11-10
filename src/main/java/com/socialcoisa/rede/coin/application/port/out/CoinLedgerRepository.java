package com.socialcoisa.rede.coin.application.port.out;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import com.socialcoisa.rede.coin.domain.CoinAccount;
import com.socialcoisa.rede.coin.domain.CoinEntry;
import com.socialcoisa.rede.coin.domain.CoinEntryPage;

public interface CoinLedgerRepository {

	Optional<CoinAccount> findAccount(String accountId);

	/**
	 * The account as stored now, row-locked until the transaction ends. A movement reads the balance,
	 * checks it and writes the next one: locked, concurrent movements on one account (the treasury
	 * pays every reward) queue instead of failing the version check.
	 */
	Optional<CoinAccount> lockAccount(String accountId);

	CoinAccount insertAccount(CoinAccount account);

	/** Atualiza o snapshot com versão otimista; devolve null quando a versão conflitou. */
	CoinAccount updateBalance(String accountId, long newBalance, long expectedVersion, long nextVersion);

	Optional<CoinEntry> lastEntry(String accountId);

	CoinEntry insertEntry(CoinEntry entry);

	boolean existsByKindAndRefId(com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind, String refId);

	/** Refs of one account's entries of a kind (one query: the shop marks everything a person bought). */
	List<String> refIdsOf(String accountId, com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind);

	List<CoinEntry> recentEntries(String accountId, int limit);

	CoinEntryPage entryPage(String accountId, int page, int size, Instant from, Instant to);

	/** Total transferido por uma conta num dia (para o teto de contas novas). */
	long transferredOn(String accountId, java.time.Instant startOfDay, java.time.Instant endOfDay);

	boolean accountExistsForUser(String userId);

	/** O lançamento de uma conta por refId (a contestação fala pelo ref da transferência). */
	Optional<CoinEntry> findByAccountIdAndRefId(String accountId, String refId);

	/** O outro lado da transferência: o lançamento de um ref em qualquer conta. */
	Optional<CoinEntry> findByRefIdAndKind(String refId, com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind);

	java.util.List<CoinAccount> allAccounts();

	/** Soma de todos os saldos snapshot — a conferência do supply fechado. */
	long totalBalance();

	/** O LEDGER é a verdade: a soma dos lançamentos reconstrói o saldo da conta. */
	long balanceFromLedger(String accountId);

	/**
	 * O LEDGER é a verdade para TODAS as contas de uma vez: um SUM agrupado por conta, em uma
	 * consulta. A conciliação noturna usa isto — um SUM por conta (N+1) dava uma ida ao banco
	 * por carteira, sobre um ledger append-only sem retenção. Conta sem lançamento não vem no
	 * mapa: o saldo reconstruído dela é 0.
	 */
	java.util.Map<String, Long> balancesFromLedger();

	/** Recompensas (kind=REWARD) com refId começando com o prefixo — tetos mensais do earned. */
	long countRewardsByRefPrefix(String prefix);

	/**
	 * Soma dos lançamentos de um tipo na janela — é daqui que sai o uso real da moeda
	 * (escrow de marketplace/serviços, transferências P2P) e a emissão automática.
	 */
	long sumAmountByKindBetween(com.socialcoisa.rede.coin.domain.CoinEntry.Kind kind, Instant from, Instant to);
}
