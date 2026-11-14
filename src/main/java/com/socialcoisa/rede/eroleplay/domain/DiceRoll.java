package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Uma rolagem: o registro imutavel de quem pediu, quando, o que foi sorteado e quanto deu.
 *
 * <p>O resultado NUNCA sai do cliente — ele descreve, o servidor sorteia com fonte de
 * aleatoriedade propria e grava. O registro e so-leitura por desenho: a classe nao tem metodo que
 * reescreva expressao, detalhe ou resultado, e o que a mesa consulta depois e o mesmo que foi
 * registrado na hora. Por isso o detalhe guarda CADA DADO: "2d6+3 deu 9" sem os dados e um numero
 * que ninguem consegue conferir, e a confianca da mesa no motor e o produto.</p>
 *
 * <p>O recorte de quem enxerga e a mesma linguagem da ficha: PRIVATE e a excecao do dono (a
 * rolagem fechada e assunto entre quem rolou e o mestre), GAME_MASTER e o padrao da mesa e
 * CAMPAIGN e o que aparece para todo o grupo. Nenhuma rolagem e publica fora da campanha.</p>
 */
public class DiceRoll {

	private final String id;
	private final String campaignId;
	private final String rollerId;
	private final String expression;
	private final List<Integer> dice;
	private final long total;
	private final String label;
	private final SheetVisibility visibility;
	private final String requestId;
	private final Instant createdAt;

	private DiceRoll(String id, String campaignId, String rollerId, String expression, List<Integer> dice,
			long total, String label, SheetVisibility visibility, String requestId, Instant createdAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.rollerId = rollerId;
		this.expression = expression;
		this.dice = List.copyOf(dice);
		this.total = total;
		this.label = label;
		this.visibility = visibility;
		this.requestId = requestId;
		this.createdAt = createdAt;
	}

	/**
	 * Roda a expressao com a fonte de aleatoriedade informada e devolve o registro pronto.
	 *
	 * <p>A fonte vem de fora de proposito: producao entrega um {@code SecureRandom}, e o teste
	 * entrega uma fonte fixa para provar o resultado sem depender de sorte. O intervalo da
	 * expressao e conferido no fim — se o total sair dele, a fonte esta quebrada, e rolar com fonte
	 * quebrada e pior que nao rolar.</p>
	 */
	public static DiceRoll roll(String id, String campaignId, String rollerId, DiceExpression expression,
			String label, SheetVisibility visibility, String requestId, RandomGenerator random, Instant now) {
		List<Integer> dice = new ArrayList<>();
		for (DiceExpression.Group group : expression.groups()) {
			for (int index = 0; index < group.count(); index++) {
				dice.add(random.nextInt(1, group.faces() + 1));
			}
		}
		long total = expression.constants().stream().mapToLong(Integer::longValue).sum();
		for (int die : dice) {
			total += die;
		}
		if (!expression.inRange(total)) {
			throw new ApiException(500, "DICE_SOURCE_BROKEN",
				"A fonte de aleatoriedade devolveu valor fora do intervalo da expressao.");
		}
		return new DiceRoll(id, campaignId, rollerId, expression.text(), dice, total, label, visibility,
			requestId, now);
	}

	/**
	 * Reconstitui um registro ja gravado. Nao sorteia, nao recalcula, nao normaliza: o registro
	 * que volta e exatamente o que foi gravado.
	 */
	public static DiceRoll restore(String id, String campaignId, String rollerId, String expression,
			List<Integer> dice, long total, String label, SheetVisibility visibility, String requestId,
			Instant createdAt) {
		return new DiceRoll(id, campaignId, rollerId, expression, dice, total, label, visibility, requestId,
			createdAt);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	/** Quem rolou. */
	public String rollerId() {
		return rollerId;
	}

	/** A expressao normalizada (ex.: {@code 2d6+3}). */
	public String expression() {
		return expression;
	}

	/** Cada dado sorteado, na ordem do grupo. */
	public List<Integer> dice() {
		return dice;
	}

	/** A soma de todos os dados e constantes. */
	public long total() {
		return total;
	}

	/** O que a mesa chamou a rolagem ("ataque", "percepcao"), quando informado. */
	public String label() {
		return label;
	}

	/** Quem enxerga esta rolagem no historico. */
	public SheetVisibility visibility() {
		return visibility;
	}

	/** O pedido do mestre que originou a rolagem, quando houve. */
	public String requestId() {
		return requestId;
	}

	public Instant createdAt() {
		return createdAt;
	}

	/** Se o publico informado enxerga esta rolagem. */
	public boolean visibleTo(SheetAudience audience) {
		return audience.sees(visibility);
	}
}
