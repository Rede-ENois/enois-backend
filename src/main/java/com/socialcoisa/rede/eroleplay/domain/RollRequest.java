package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O pedido de rolagem que o MESTRE faz e o jogador responde.
 *
 * <p>E o mecanismo da mesa para pedir um teste sem quebrar a cena: o mestre pede ("peca um teste
 * de percepcao"), a rolagem nasce apontando para o pedido, e o pedido aponta para a rolagem
 * respondida. O estado existe para a mesa saber o que ainda falta responder — e para o mesmo
 * pedido nao ser respondido duas vezes por engano.</p>
 *
 * <p>O pedido nasce com a visibilidade maxima que a resposta pode ter; quem responde pode fechar
 * mais, como na ficha. O pedido aceito e imutavel: recusar de novo, reabrir ou reagendar nao
 * existe — o mestre faz um pedido novo.</p>
 */
public class RollRequest {

	private final String id;
	private final String campaignId;
	private final String masterId;
	private final String playerId;
	private final String expression;
	private final String label;
	private final SheetVisibility maxVisibility;
	private final String reason;
	private final RollRequestStatus status;
	private final String answeredRollId;
	private final Instant createdAt;
	private final Instant answeredAt;

	private RollRequest(String id, String campaignId, String masterId, String playerId, String expression,
			String label, SheetVisibility maxVisibility, String reason, RollRequestStatus status,
			String answeredRollId, Instant createdAt, Instant answeredAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.masterId = masterId;
		this.playerId = playerId;
		this.expression = expression;
		this.label = label;
		this.maxVisibility = maxVisibility;
		this.reason = reason;
		this.status = status;
		this.answeredRollId = answeredRollId;
		this.createdAt = createdAt;
		this.answeredAt = answeredAt;
	}

	/** O mestre pede uma rolagem a um jogador. */
	public static RollRequest open(String id, String campaignId, String masterId, String playerId,
			DiceExpression expression, String label, SheetVisibility maxVisibility, String reason, Instant now) {
		return new RollRequest(id, campaignId, masterId, playerId, expression.text(), label, maxVisibility,
			reason, RollRequestStatus.PENDING, null, now, null);
	}

	/** Reconstitui o pedido gravado. */
	public static RollRequest restore(String id, String campaignId, String masterId, String playerId,
			String expression, String label, SheetVisibility maxVisibility, String reason,
			RollRequestStatus status, String answeredRollId, Instant createdAt, Instant answeredAt) {
		return new RollRequest(id, campaignId, masterId, playerId, expression, label, maxVisibility, reason,
			status, answeredRollId, createdAt, answeredAt);
	}

	/**
	 * Marca o pedido como respondido pela rolagem informada.
	 *
	 * <p>Pedido respondido duas vezes e recusado, nao corrigido: a mesa precisa poder confiar que
	 * o resultado que ficou registrado e o primeiro que saiu — rolagem refeita ate dar o numero
	 * bom e exatamente o que o registro imutavel existe para impedir.</p>
	 */
	public RollRequest answer(String rollId, Instant now) {
		if (status != RollRequestStatus.PENDING) {
			throw new ApiException(409, "ROLL_REQUEST_ALREADY_ANSWERED",
				"Este pedido de rolagem ja foi respondido.");
		}
		return new RollRequest(id, campaignId, masterId, playerId, expression, label, maxVisibility, reason,
			RollRequestStatus.ANSWERED, rollId, createdAt, now);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String masterId() {
		return masterId;
	}

	/** O jogador a quem o pedido foi feito — so ele responde. */
	public String playerId() {
		return playerId;
	}

	/** A expressao pedida, ja normalizada. */
	public String expression() {
		return expression;
	}

	public String label() {
		return label;
	}

	/** A visibilidade maxima que a resposta pode ter. */
	public SheetVisibility maxVisibility() {
		return maxVisibility;
	}

	/** O motivo que o mestre declarou ("percepcao na porta"), quando informado. */
	public String reason() {
		return reason;
	}

	public RollRequestStatus status() {
		return status;
	}

	/** A rolagem que respondeu o pedido, quando respondido. */
	public String answeredRollId() {
		return answeredRollId;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant answeredAt() {
		return answeredAt;
	}
}
