package com.socialcoisa.rede.coin.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinAccount;
import com.socialcoisa.rede.coin.domain.CoinEntry;
import com.socialcoisa.rede.coin.domain.CoinEntryPage;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A faísca: a moeda interna do Énóis.
 *
 * Supply FIXO definido no genesis (nunca há emissão posterior): a tesouraria nasce com um
 * volume interno fixo e é DELA que saem o airdrop (100 por conta) e os ganhos. Transferências
 * P2P movem faíscas entre contas sem criar nem queimar. Estilo Pix: instantânea, por chave
 * (username), irreversível — a moderação reverte com lançamento espelho, nunca DELETE.
 *
 * Integridade: ledger append-only com hash encadeado por conta + snapshot otimista +
 * idempotência por (conta, chave). O total da rede não é público.
 */
@Service
public class CoinService {

	private static final Logger LOG = LoggerFactory.getLogger(CoinService.class);

	public static final String TREASURY_ACCOUNT = "coin_tesouraria";
	/** Conta de RETENÇÃO do escrow: não pertence a ninguém; as faíscas ficam paradas aqui. */
	public static final String ESCROW_ACCOUNT = "coin_escrow";
	/** Taxa da plataforma sobre vendas (Fase 4/D1): 10% do preço, ida para a tesouraria. */
	public static final long MARKETPLACE_FEE_PERCENT = 10L;
	/**
	 * A faísca é FRACIONADA: o ledger inteiro conta MILÉSIMOS (1 faísca = 1.000 mf),
	 * como os centavos do real — inteiro no banco (sem float), decimal na tela. O
	 * fracionamento existe porque a cotação de referência pode dar centavos por faísca.
	 */
	public static final long SCALE = 1_000L;

	public static long FAIS(long f) {
		return f * SCALE;
	}

	/** Volume interno fixo do genesis. Não é público; nunca muda. */
	private static final long TREASURY_GENESIS = FAIS(2_000_000L);
	public static final long AIRDROP_PER_USER = FAIS(100L);
	/** Presença diária (plano C2): 5/dia, bônus de 25 a cada 7 dias seguidos. */
	public static final long DAILY_PRESENCE = FAIS(5L);
	public static final long STREAK_BONUS = FAIS(25L);
	/** Teto de envio para contas com menos de 7 dias. */
	private static final long NEW_ACCOUNT_DAILY_CAP = FAIS(500L);
	private static final Duration NEW_ACCOUNT_WINDOW = Duration.ofDays(7);

	private final CoinLedgerRepository ledger;
	private final CoinRateService rateService;
	private final com.socialcoisa.rede.shared.security.SecretCipher cipher;
	private final ActionThrottle throttle;
	private final TransferDedupe dedupe;
	private final int transfersPerMinute;
	private final int transfersPerHour;
	private final Clock clock;

	public CoinService(CoinLedgerRepository ledger, CoinRateService rateService,
			com.socialcoisa.rede.shared.security.SecretCipher cipher, ActionThrottle throttle,
			TransferDedupe dedupe, Clock clock,
			@Value("${rede.coin.transfer-per-minute:10}") int transfersPerMinute,
			@Value("${rede.coin.transfer-per-hour:60}") int transfersPerHour) {
		this.ledger = ledger;
		this.rateService = rateService;
		this.cipher = cipher;
		this.throttle = throttle;
		this.dedupe = dedupe;
		this.clock = clock;
		this.transfersPerMinute = Math.max(1, Math.min(transfersPerMinute, 1_000));
		this.transfersPerHour = Math.max(this.transfersPerMinute, Math.min(transfersPerHour, 10_000));
	}

	public record Wallet(long balance, String chave, long escala, long cotacaoBrlCents, List<CoinEntry> entries) {
	}

	/** Conta da moeda: cria a conta, executa o airdrop do genesis e marca a presença do dia. */
	@Transactional
	public Wallet wallet(String accountId, String username) {
		CoinAccount account = ensureAccount(accountId);
		ensureGenesis(accountId, username);
		ensureDailyPresence(accountId);
		List<CoinEntry> extrato = ledger.recentEntries(accountId, 50).stream()
			.map(e -> e.withMessage(decifrar(e.message())))
			.toList();
		return new Wallet(ensureAccount(accountId).balance(), username, SCALE,
			rateService.currentBrlCents(), extrato);
	}

	/**
	 * Extrato para AUDITORIA (incidente/painel): lê o que existe e NÃO credita nada.
	 *
	 * O `wallet()` normal garante genesis e presença (efeito colateral de abrir a carteira) — numa
	 * auditoria isso criaria conta e airdrop para quem estava sendo investigado.
	 */
	public Wallet walletSnapshot(String accountId, String username, int limit) {
		var account = ledger.findAccount(accountId);
		long balance = account.map(CoinAccount::balance).orElse(0L);
		List<CoinEntry> entries = account.isEmpty() ? List.of()
			: ledger.recentEntries(accountId, Math.max(1, Math.min(limit, 200))).stream()
				.map(e -> e.withMessage(decifrar(e.message())))
				.toList();
		return new Wallet(balance, username, SCALE, rateService.currentBrlCents(), entries);
	}

	/**
	 * Presença diária (plano C2): +5 por dia; a cada 7 dias consecutivos de presença,
	 * +25 de bônus. Idempotente por conta+dia — abrir a carteira mil vezes não duplica.
	 */
	private void ensureDailyPresence(String accountId) {
		LocalDate today = LocalDate.now(clock);
		String ref = "presenca:" + accountId + ":" + today;
		if (ledger.existsByKindAndRefId(CoinEntry.Kind.REWARD, ref)) {
			return;
		}
		Instant now = Instant.now(clock);
		// Contagem de presenças na janela de 7 dias: o 7º dia consecutivo ganha o bônus.
		long dias = 1; // hoje conta
		for (int i = 1; i <= 7; i++) {
			String prevRef = "presenca:" + accountId + ":" + today.minusDays(i);
			if (!ledger.existsByKindAndRefId(CoinEntry.Kind.REWARD, prevRef)) {
				break;
			}
			dias++;
		}
		long amount = DAILY_PRESENCE;
		String message = "Presença diária";
		if (dias >= 7) {
			amount += STREAK_BONUS;
			message = "Presença diária (bônus de " + dias + " dias seguidos)";
		}
		ensureTreasury();
		grant(TREASURY_ACCOUNT, -amount, CoinEntry.Kind.GENESIS_TREASURY, "presenca-tesouraria:" + ref,
			message, now);
		grant(accountId, amount, CoinEntry.Kind.REWARD, ref, message, now);
	}

	/** Airdrop idempotente: 100 faíscas da tesouraria, uma vez por conta. Sem exceção por username. */
	private void ensureGenesis(String accountId, String username) {
		if (ledger.existsByKindAndRefId(com.socialcoisa.rede.coin.domain.CoinEntry.Kind.AIRDROP,
				"airdrop:" + accountId)) {
			return;
		}
		ensureTreasury();
		long amount = AIRDROP_PER_USER;
		grant(TREASURY_ACCOUNT, -amount, CoinEntry.Kind.GENESIS_TREASURY, "airdrop-tesouraria:" + accountId,
			"Distribuição inicial", Instant.now(clock));
		grant(accountId, amount, CoinEntry.Kind.AIRDROP, "airdrop:" + accountId, "Distribuição inicial",
			Instant.now(clock));
	}

	/** Conta da tesouraria: nasce com o volume fixo do genesis na primeira vez que é tocada. */
	private CoinAccount ensureTreasury() {
		CoinAccount treasury = ledger.findAccount(TREASURY_ACCOUNT)
			.orElseGet(() -> {
				CoinAccount created = ledger.insertAccount(CoinAccount.create(TREASURY_ACCOUNT, 0, Instant.now(clock)));
				grant(TREASURY_ACCOUNT, TREASURY_GENESIS, CoinEntry.Kind.GENESIS_TREASURY, "genesis:tesouraria",
					"Gênese da faísca (supply fixo)", Instant.now(clock));
				return ledger.findAccount(TREASURY_ACCOUNT).orElse(created);
			});
		return treasury;
	}

	private CoinAccount ensureAccount(String accountId) {
		return ledger.findAccount(accountId)
			.orElseGet(() -> ledger.insertAccount(CoinAccount.create(accountId, 0, Instant.now(clock))));
	}

	/** Lança uma entrada na conta (débito se amount < 0) atualizando o snapshot com hash encadeado. */
	private CoinEntry grant(String accountId, long amount, CoinEntry.Kind kind, String refId, String message,
			Instant now) {
		ensureAccount(accountId);
		CoinAccount account = ledger.findAccount(accountId)
			.orElseThrow(() -> new ApiException(500, "COIN_ACCOUNT_MISSING", "Conta da moeda nao encontrada."));
		if (amount < 0 && account.balance() + amount < 0) {
			throw new ApiException(400, "INSUFFICIENT_SPARKS", "Faíscas insuficientes.");
		}
		CoinEntry last = ledger.lastEntry(accountId).orElse(null);
		String prevHash = last != null ? last.hash() : "0".repeat(64);
		String idem = kind.name().toLowerCase(Locale.ROOT) + ":" + refId;
		String hash = hashOf(prevHash, accountId, amount, kind, refId, idem);
		CoinEntry entry = CoinEntry.create("cent_" + UUID.randomUUID().toString().replace("-", ""), accountId,
			amount, kind, refId, cifrar(message), idem, prevHash, hash, now);
		CoinEntry saved = ledger.insertEntry(entry);
		long nextBalance = account.balance() + amount;
		CoinAccount updated = ledger.updateBalance(accountId, nextBalance, account.version(), account.version() + 1);
		if (updated == null) {
			throw new ApiException(409, "COIN_CONCURRENCY", "Movimentação concorrente; tente de novo.");
		}
		return saved;
	}

	private static final String ENC_PREFIX = "enc:v1:";

	/** A mensagem da transferência é DADO PESSOAL: cifra em repouso (AES-GCM) quando a chave existe. */
	private String cifrar(String message) {
		if (message == null || message.isBlank() || !cipher.isEnabled()) {
			return message;
		}
		return ENC_PREFIX + cipher.encrypt(message);
	}

	private String decifrar(String message) {
		if (message == null || !message.startsWith(ENC_PREFIX)) {
			return message;
		}
		if (!cipher.isEnabled()) {
			return "[mensagem protegida]";
		}
		try {
			return cipher.decrypt(message.substring(ENC_PREFIX.length()));
		} catch (RuntimeException e) {
			return "[mensagem protegida]";
		}
	}

	private String hashOf(String prevHash, String accountId, long amount, CoinEntry.Kind kind, String refId,
			String idem) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String payload = prevHash + "|" + accountId + "|" + amount + "|" + kind + "|" + refId + "|" + idem;
			return HexFormat.of().formatHex(digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (Exception impossible) {
			throw new IllegalStateException("SHA-256 indisponível");
		}
	}

	/** Transferência P2P estilo Pix: por username, instantânea, irreversível. */
	@Transactional
	public CoinEntry transfer(String fromAccountId, String toUsername, long amount, String message, String toUserId,
			String toUsernameResolved) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O valor precisa ser maior que zero.");
		}
		if (fromAccountId.equals(toUserId)) {
			throw new ApiException(400, "SELF_TRANSFER", "Não dá para transferir para você mesmo.");
		}
		ensureGenesis(fromAccountId, fromAccountId);
		CoinAccount from = ensureAccount(fromAccountId);
		CoinAccount to = ensureAccount(toUserId);
		// Teto de conta nova: os primeiros 7 dias enviam até 500/dia.
		if (isNewAccount(from)) {
			Instant start = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
			long sentToday = ledger.transferredOn(fromAccountId, start, start.plus(Duration.ofDays(1)));
			if (sentToday + amount > NEW_ACCOUNT_DAILY_CAP) {
				throw new ApiException(403, "NEW_ACCOUNT_LIMIT",
					"Contas novas enviam até " + (NEW_ACCOUNT_DAILY_CAP / SCALE)
						+ " faíscas por dia nos primeiros 7 dias.");
			}
		}
		if (from.balance() < amount) {
			throw new ApiException(400, "INSUFFICIENT_SPARKS", "Faíscas insuficientes.");
		}
		// O MESMO Pix repetido em segundos (duplo clique/retry) devolve a entrada da primeira vez.
		CoinEntry repetida = dedupe.recent(fromAccountId, toUserId, amount, message);
		if (repetida != null) {
			LOG.info("faisca: transferencia repetida ignorada de {} para {} de {} mf (ref {})",
				fromAccountId, toUserId, amount, repetida.refId());
			return repetida;
		}
		// Teto por conta: 141 POSTs em rajada (incidente 22/10) param aqui, antes do ledger.
		throttle.exigePermitido("transferencia-minuto", fromAccountId, transfersPerMinute, Duration.ofMinutes(1));
		throttle.exigePermitido("transferencia-hora", fromAccountId, transfersPerHour, Duration.ofHours(1));

		String transferRef = "p2p_" + UUID.randomUUID().toString().replace("-", "");
		Instant now = clock.instant();
		CoinEntry out = grant(fromAccountId, -amount, CoinEntry.Kind.TRANSFER_OUT, transferRef,
			message == null ? "" : message, now);
		grant(toUserId, amount, CoinEntry.Kind.TRANSFER_IN, transferRef, message == null ? "" : message, now);
		dedupe.remember(fromAccountId, toUserId, amount, message, out);
		// Auditoria do dinheiro: sem a mensagem (dado pessoal), com o suficiente para reconstruir
		// o que aconteceu quando os logs estiverem sendo guardados de verdade.
		LOG.info("faisca: transferencia de {} para {} de {} mf (ref {})", fromAccountId, toUserId, amount,
			transferRef);
		return out;
	}

	/**
	 * Escrow (marketplace/serviços): as faíscas SAEM do comprador e ficam RETIDAS na conta de
	 * escrow — não gastas, não sacáveis por ninguém até a decisão (liberação ou reembolso).
	 */
	@Transactional
	public CoinEntry escrowHold(String buyerAccountId, long amount, String purchaseRef, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O preço precisa ser positivo.");
		}
		Instant now = clock.instant();
		CoinEntry out = grant(buyerAccountId, -amount, CoinEntry.Kind.ESCROW_OUT, purchaseRef,
			message == null ? "" : message, now);
		grant(ESCROW_ACCOUNT, amount, CoinEntry.Kind.ESCROW_HOLD, purchaseRef, message == null ? "" : message, now);
		return out;
	}

	/**
	 * Liberação do escrow: vendedor recebe preço − 10% de taxa; a taxa volta para a
	 * tesouraria (o supply não muda — as faíscas só mudam de conta).
	 */
	@Transactional
	public void escrowRelease(String sellerAccountId, long amount, String purchaseRef, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O preço precisa ser positivo.");
		}
		Instant now = clock.instant();
		long fee = amount * MARKETPLACE_FEE_PERCENT / 100;
		long net = amount - fee;
		grant(ESCROW_ACCOUNT, -amount, CoinEntry.Kind.ESCROW_RELEASE, purchaseRef, message == null ? "" : message, now);
		grant(sellerAccountId, net, CoinEntry.Kind.ESCROW_IN, purchaseRef, message == null ? "" : message, now);
		if (fee > 0) {
			ensureTreasury();
			grant(TREASURY_ACCOUNT, fee, CoinEntry.Kind.FEE_IN, purchaseRef, "Taxa de marketplace", now);
		}
	}

	/** Reembolso do escrow: volta INTEGRAL para o comprador. */
	@Transactional
	public void escrowRefund(String buyerAccountId, long amount, String purchaseRef, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O preço precisa ser positivo.");
		}
		Instant now = clock.instant();
		// A saida do escrow usa o MESMO ref da liberacao (escrowRelease): a chave de idempotencia
		// (kind:ref, unica por conta) fica igual nos dois caminhos, e o banco recusa o segundo.
		// Com refs diferentes, liberar e reembolsar a mesma compra ao mesmo tempo (o comprador
		// pedindo reembolso enquanto o job libera, ou aceitar e recusar em duas abas) pagava o
		// vendedor E devolvia ao comprador, tirando do escrow o dinheiro de outras compras.
		grant(ESCROW_ACCOUNT, -amount, CoinEntry.Kind.ESCROW_RELEASE, purchaseRef,
			message == null ? "" : message, now);
		grant(buyerAccountId, amount, CoinEntry.Kind.REFUND_IN, purchaseRef + ":refund",
			message == null ? "" : message, now);
	}

	/**
	 * Compra na LOJA da plataforma (plano C3): o valor sai do comprador e VOLTA para a
	 * tesouraria — o supply não muda, a faísca só circula de volta. Idempotente por ref
	 * (a mesma compra nunca duplica; quem já tem o item não paga duas vezes).
	 */
	@Transactional
	public void purchase(String accountId, long amount, String ref, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O preço precisa ser positivo.");
		}
		if (ledger.existsByKindAndRefId(CoinEntry.Kind.PURCHASE, ref)) {
			return;
		}
		ensureTreasury();
		Instant now = Instant.now(clock);
		grant(accountId, -amount, CoinEntry.Kind.PURCHASE, ref, message, now);
		grant(TREASURY_ACCOUNT, amount, CoinEntry.Kind.GENESIS_TREASURY, "loja-tesouraria:" + ref, message, now);
	}

	/** Recompensas do mês por prefixo (teto mensal do earned, ex.: microprojetos 3/mês). */
	public long rewardCountByPrefix(String prefix) {
		return ledger.countRewardsByRefPrefix(prefix);
	}

	/**
	 * Prêmio concedido por papel com permissão (plano C2: prêmios de evento/comunidade —
	 * auditado: cada grant vira lançamento do ledger com o motivo). Sai da tesouraria;
	 * supply fixo preservado.
	 */
	@Transactional
	public void grantPrize(String accountId, long amount, String ref, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O valor precisa ser positivo.");
		}
		if (ledger.existsByKindAndRefId(CoinEntry.Kind.REWARD, ref)) {
			return;
		}
		ensureTreasury();
		Instant now = Instant.now(clock);
		grant(TREASURY_ACCOUNT, -amount, CoinEntry.Kind.GENESIS_TREASURY, "premio-tesouraria:" + ref, message, now);
		grant(accountId, amount, CoinEntry.Kind.REWARD, ref, message, now);
	}

	/** A conta já ganhou essa recompensa? (idempotência legível para os hooks do earned) */
	public boolean hasReward(String ref) {
		return ledger.existsByKindAndRefId(CoinEntry.Kind.REWARD, ref);
	}

	/**
	 * Recompensa de earned (plano C2): sai da tesouraria, entra na conta — supply fixo
	 * preservado. Idempotente por ref (a mesma recompensa nunca duplica).
	 */
	@Transactional
	public void reward(String accountId, long amount, String ref, String message) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O valor precisa ser positivo.");
		}
		if (ledger.existsByKindAndRefId(CoinEntry.Kind.REWARD, ref)) {
			return;
		}
		ensureTreasury();
		Instant now = Instant.now(clock);
		grant(TREASURY_ACCOUNT, -amount, CoinEntry.Kind.GENESIS_TREASURY, "reward-tesouraria:" + ref, message, now);
		grant(accountId, amount, CoinEntry.Kind.REWARD, ref, message, now);
	}

	/**
	 * REVERSÃO de transferência (contestation de golpe, decisão da moderação): espelho no
	 * ledger — o receptor devolve e o remetente recebe de volta. Append-only: nada de
	 * UPDATE/DELETE; os lançamentos espelho contam com o motivo.
	 */
	@Transactional
	public void reverseTransfer(String receiverId, String senderId, long amount, String originalRef, String reason) {
		if (amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "O valor da reversão precisa ser positivo.");
		}
		Instant now = Instant.now(clock);
		grant(receiverId, -amount, CoinEntry.Kind.REVERSAL_OUT, originalRef + ":reversao", reason, now);
		grant(senderId, amount, CoinEntry.Kind.REVERSAL_IN, originalRef + ":reversao", reason, now);
	}

	/** Visão de supply: total fixo do gênese, o que ficou no cofre e o que circula. */
	public record SupplyView(long total, long cofre, long circulacao, long somaSaldos, boolean coerente) {
	}

	/**
	 * A soma de todos os saldos TEM que ser igual ao total do gênese: nada cria faíscas, tudo gira
	 * do valor que já existe. Divergência é bug ou fraude — o painel mostra e a investigação vem.
	 */
	@Transactional(readOnly = true)
	public SupplyView supply() {
		long total = TREASURY_GENESIS;
		var tesouraria = ledger.findAccount(TREASURY_ACCOUNT);
		long cofre = tesouraria.map(CoinAccount::balance).orElse(0L);
		long soma = ledger.totalBalance();
		// Pré-gênese (nenhuma carteira acessada ainda) não é divergência: só é alarme quando a
		// tesouraria existe e a soma dos saldos não fecha com o total.
		boolean coerente = tesouraria.isPresent() ? soma == total : soma == 0;
		return new SupplyView(total, cofre, total - cofre, soma, coerente);
	}

	/**
	 * Reconciliação (plano C4): o LEDGER é a verdade — reconstrói o saldo de cada conta
	 * somando os lançamentos e compara com o snapshot. Divergência LOGA ALERTA (nada de
	 * auto-correção silenciosa: a investigação vem primeiro). Roda de madrugada.
	 */
	@Scheduled(cron = "0 17 4 * * *")
	@Transactional(readOnly = true)
	public void reconcile() {
		for (CoinAccount account : ledger.allAccounts()) {
			long rebuilt = ledger.balanceFromLedger(account.accountId());
			if (rebuilt != account.balance()) {
				LOG.error("coin_reconciliation_divergence accountId={} snapshot={} ledger={}", account.accountId(),
					account.balance(), rebuilt);
			}
		}
	}

	private boolean isNewAccount(CoinAccount account) {
		return Duration.between(account.createdAt(), clock.instant()).compareTo(NEW_ACCOUNT_WINDOW) < 0;
	}

	@Transactional(readOnly = true)
	public long balance(String accountId) {
		return ensureAccount(accountId).balance();
	}

	@Transactional(readOnly = true)
	public List<CoinEntry> entries(String accountId, int limit) {
		return ledger.recentEntries(accountId, limit);
	}

	@Transactional(readOnly = true)
	public CoinEntryPage extract(String accountId, int page, int size, Instant from, Instant to) {
		var result = ledger.entryPage(accountId, page, size, from, to);
		return new CoinEntryPage(result.entries().stream()
				.map(e -> e.withMessage(decifrar(e.message()))).toList(), result.hasNext());
	}
}
