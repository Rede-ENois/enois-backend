package com.socialcoisa.rede.doacoes.domain;

import java.time.Instant;
import java.util.UUID;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Uma tentativa de doação: o doador, o copia e cola que ele recebeu, e até onde o recebedor
 * conseguiu confirmar.
 *
 * <p>O txid é o elo com o dinheiro (o app do pagador devolve o campo EMV 62-05 intacto); ele é
 * público — quem tem o código na mão consegue gerar o mesmo pagamento —, então o txid sozinho
 * NÃO confirma nada. A confirmação só sai de um comprovante lido (valor igual ao gerado, mais o
 * identificador fim a fim de cada pix que veio) ou de uma linha de ENTRADA do extrato da conta de
 * quem recebe (a revisão).</p>
 *
 * <p>O comprovante NÃO é guardado: ele é lido, o que interessa é extraído, e o texto é descartado.
 * O que fica na linha é o resultado da leitura — a decisão, o valor e os identificadores que já
 * foram usados, para o mesmo comprovante não confirmar outra conta.</p>
 */
public record Doacao(
		String id,
		String userId,
		String txid,
		long amountCents,
		java.util.List<String> e2eIds,
		DoacaoStatus status,
		DoacaoStatus.Origem origem,
		Instant createdAt,
		Instant confirmedAt,
		String decidedBy) {

	/** Teto de uma doação. O selo é pelo gesto; valor acima disso vai para a conversa direta. */
	public static final long MAX_AMOUNT_CENTS = 500_000;
	public static final long MIN_AMOUNT_CENTS = 100;

	/** Prefixo do txid: o resto é aleatório, alfabético e numérico, como o campo EMV exige. */
	private static final String TXID_PREFIX = "ENOIS";
	private static final char[] ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

	public static Doacao open(String userId, String txid, long amountCents, Instant now) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("USER_ID_REQUIRED");
		}
		if (!validTxid(txid)) {
			throw new IllegalArgumentException("TXID_INVALID");
		}
		if (amountCents < MIN_AMOUNT_CENTS || amountCents > MAX_AMOUNT_CENTS) {
			throw new ApiException(400, "DONATION_AMOUNT_OUT_OF_RANGE",
				"O valor da doação fica entre R$ 1,00 e R$ 5.000,00.");
		}
		return new Doacao("dona_" + UUID.randomUUID().toString().replace("-", ""), userId, txid, amountCents,
			java.util.List.of(), DoacaoStatus.ABERTA, null, now, null, null);
	}

	/**
	 * Um txid novo: maiúsculo e numérico (sem 0, 1, I e O, que se leem errado), cabendo com folga
	 * nos 25 caracteres do campo 62-05, com 84 bits de aleatoriedade.
	 */
	public static String newTxid() {
		StringBuilder sb = new StringBuilder(TXID_PREFIX);
		java.security.SecureRandom random = new java.security.SecureRandom();
		while (sb.length() < 18) {
			sb.append(ALFABETO[random.nextInt(ALFABETO.length)]);
		}
		return sb.toString();
	}

	public static boolean validTxid(String txid) {
		return txid != null && txid.matches("[A-Za-z0-9]{1," + BrCode.TXID_MAX + "}");
	}

	/**
	 * O comprovante do doador foi lido: o valor bate e veio pelo menos um identificador fim a fim.
	 * Só os identificadores ficam; o texto do comprovante não.
	 */
	public Doacao confirmByDonor(java.util.List<String> pixIds, Instant now) {
		if (status != DoacaoStatus.ABERTA) {
			throw new ApiException(409, "DONATION_ALREADY_DECIDED", "Esta doação já foi decidida.");
		}
		if (pixIds == null || pixIds.isEmpty()) {
			throw new ApiException(400, "DONATION_PROOF_MISSING",
				"O comprovante precisa trazer o identificador fim a fim do Pix.");
		}
		return new Doacao(id, userId, txid, amountCents, java.util.List.copyOf(pixIds),
			DoacaoStatus.CONFIRMADA, DoacaoStatus.Origem.DOADOR, createdAt, now, null);
	}

	/**
	 * A revisão achou a entrada no extrato da conta com o mesmo valor. O identificador do extrato
	 * soma ao que já foi lido do comprovante (serve para o mesmo pix não confirmar outra conta), e a
	 * doação fica com origem CONTA — a prova forte.
	 */
	public Doacao confirmByAccount(String idDoExtrato, Instant now, String reviewerId) {
		if (status == DoacaoStatus.RECUSADA) {
			throw new ApiException(409, "DONATION_ALREADY_DECIDED", "Esta doação já foi recusada.");
		}
		java.util.List<String> ids = new java.util.ArrayList<>(e2eIds);
		if (idDoExtrato != null && !idDoExtrato.isBlank() && !ids.contains(idDoExtrato.trim())) {
			ids.add(idDoExtrato.trim());
		}
		return new Doacao(id, userId, txid, amountCents, java.util.List.copyOf(ids), DoacaoStatus.CONFIRMADA,
			DoacaoStatus.Origem.CONTA, createdAt, confirmedAt != null ? confirmedAt : now, reviewerId);
	}

	/**
	 * O selo dado à mão por um administrador (o comprovante não foi reconhecido, ou a doação foi
	 * feita por outro caminho). Nasce <b>já confirmada</b> e com valor zero: valor zero diz "esta
	 * linha não é dinheiro lido, é o selo registrado" — o extrato e os totais nunca a somam como
	 * doação, e a tela de quem doou não mostra um valor que ninguém pagou.
	 *
	 * <p>Como toda doação confirmada, só há uma registrada por pessoa (o serviço recusa a segunda):
	 * o selo é pelo gesto, e não se acumula por clique.</p>
	 */
	public static Doacao manualSeal(String userId, Instant now, String adminId) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("USER_ID_REQUIRED");
		}
		return new Doacao("dona_" + UUID.randomUUID().toString().replace("-", ""), userId, "MANUAL-" + newTxid(),
			0L, java.util.List.of(), DoacaoStatus.CONFIRMADA, DoacaoStatus.Origem.MANUAL, now, now, adminId);
	}

	/** O extrato da conta não mostrou o pix. Revisão humana, com o nome de quem decidiu. */
	public Doacao reject(String byUserId) {
		if (status != DoacaoStatus.ABERTA) {
			return this;
		}
		return new Doacao(id, userId, txid, amountCents, e2eIds, DoacaoStatus.RECUSADA,
			DoacaoStatus.Origem.REVISAO, createdAt, confirmedAt, byUserId);
	}
}
