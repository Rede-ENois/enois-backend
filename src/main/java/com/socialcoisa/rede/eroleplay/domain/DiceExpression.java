package com.socialcoisa.rede.eroleplay.domain;

import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A expressao de dados que a mesa pediu — interpretada e sorteada NO SERVIDOR.
 *
 * <p>"Nunca calculada no cliente" vale para o resultado e para o sorteio: o cliente descreve o que
 * quer rolar ({@code 2d6+3}, {@code 1d20}, {@code 4d6}) e o servidor interpreta, sorteia e
 * registra. A gramatica e pequena de proposito — soma de grupos {@code NdF} e constantes — porque
 * o que nao esta na gramatica nao pode ser sorteado de forma intercambiavel entre sistemas.</p>
 *
 * <p>A interpretacao rejeita de forma explicita: expressao vazia, grupo sem faces, faces fora da
 * faixa suportada (1-1000), quantidade acima do teto (100), constante acima do teto (+/-999.999) e
 * mais de oito grupos ou constantes. Nada de "talvez seja 1d20": se nao da para interpretar, e
 * erro com codigo proprio, nunca um palpite silencioso.</p>
 */
public final class DiceExpression {

	/** Faces que o motor conhece: o limite e de leitura, nao de regra. */
	public static final int MAX_FACES = 1000;

	/** Quantidade maxima de dados num unico grupo: proteger o servidor, nao a regra. */
	public static final int MAX_DICE = 100;

	/** Teto absoluto de uma constante: o mesmo teto do campo numerico da ficha. */
	public static final int MAX_CONSTANT = 999_999;

	/** Grupos e constantes por expressao: uma rolagem legivel nao precisa de mais. */
	private static final int MAX_TERMS = 8;

	private final String text;
	private final List<Group> groups;
	private final List<Integer> constants;

	private DiceExpression(String text, List<Group> groups, List<Integer> constants) {
		this.text = text;
		this.groups = List.copyOf(groups);
		this.constants = List.copyOf(constants);
	}

	/**
	 * Interpreta a expressao informada.
	 *
	 * @throws ApiException com codigo proprio quando a expressao nao e rolagem de dado
	 */
	public static DiceExpression parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "DICE_EXPRESSION_REQUIRED", "Informe o que rolar.");
		}
		String expression = raw.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
		if (expression.length() > 60) {
			throw new ApiException(400, "DICE_EXPRESSION_TOO_LONG",
				"A expressao de rolagem deve ter ate 60 caracteres.");
		}
		if (!expression.matches("[0-9d+\\-]+")) {
			throw new ApiException(400, "DICE_EXPRESSION_INVALID",
				"A expressao aceita dados (NdF), somas e subtracoes.");
		}
		List<Group> groups = new ArrayList<>();
		List<Integer> constants = new ArrayList<>();
		for (String term : expression.split("(?=[+-])")) {
			if (term.isEmpty() || term.equals("+") || term.equals("-")) {
				throw new ApiException(400, "DICE_EXPRESSION_INVALID",
					"A expressao nao pode ter operador solto.");
			}
			if (term.contains("d")) {
				if (groups.size() == MAX_TERMS) {
					throw new ApiException(400, "DICE_GROUPS_TOO_MANY",
						"A expressao aceita ate 8 grupos de dados.");
				}
				groups.add(Group.parse(term));
			}
			else {
				if (constants.size() == MAX_TERMS) {
					throw new ApiException(400, "DICE_CONSTANTS_TOO_MANY",
						"A expressao aceita ate 8 constantes.");
				}
				constants.add(constant(term));
			}
		}
		if (groups.isEmpty()) {
			// Constante sozinha nao e rolagem: registrar "5" como resultado de dado seria um registro
			// sem dado nenhum, e o historico da mesa perderia o sentido.
			throw new ApiException(400, "DICE_EXPRESSION_INVALID",
				"A expressao precisa de pelo menos um dado (NdF).");
		}
		return new DiceExpression(expression, groups, constants);
	}

	private static Integer constant(String term) {
		try {
			long value = Long.parseLong(term);
			// Comparacao com sinal, e nao abs(): o menor long nao tem simetrico e passaria do teto.
			if (value < -MAX_CONSTANT || value > MAX_CONSTANT) {
				throw new ApiException(400, "DICE_CONSTANT_TOO_LARGE",
					"Cada constante vai ate " + MAX_CONSTANT + ".");
			}
			return Math.toIntExact(value);
		}
		catch (NumberFormatException error) {
			throw new ApiException(400, "DICE_EXPRESSION_INVALID",
				"A expressao aceita dados (NdF), somas e subtracoes.");
		}
	}

	/** O texto normalizado da expressao — o que vai para o registro imutavel. */
	public String text() {
		return text;
	}

	/** Os grupos de dados. */
	public List<Group> groups() {
		return groups;
	}

	/** As constantes, com sinal. */
	public List<Integer> constants() {
		return constants;
	}

	/** O menor resultado possivel: todo dado vale 1 e as constantes entram como sao. */
	public long minTotal() {
		long total = 0;
		for (Group group : groups) {
			total += (long) group.count();
		}
		for (int constant : constants) {
			total += constant;
		}
		return total;
	}

	/** O maior resultado possivel: todo dado no topo e as constantes entram como sao. */
	public long maxTotal() {
		long total = 0;
		for (int constant : constants) {
			total += constant;
		}
		for (Group group : groups) {
			total += (long) group.count() * group.faces();
		}
		return total;
	}

	/** O intervalo e fechado: todo resultado valido pertence a ele. */
	public boolean inRange(long total) {
		return total >= minTotal() && total <= maxTotal();
	}

	/** Um grupo {@code NdF}: N dados de F faces. */
	public record Group(int count, int faces) {

		static Group parse(String term) {
			String[] parts = term.split("d", -1);
			if (parts.length != 2) {
				throw new ApiException(400, "DICE_EXPRESSION_INVALID",
					"Cada grupo de dados e NdF: quantidade, d e faces.");
			}
			int count = parts[0].isEmpty() ? 1 : parseCount(parts[0]);
			int faces = parseFaces(parts[1]);
			return new Group(count, faces);
		}

		private static int parseCount(String raw) {
			try {
				int value = Integer.parseInt(raw);
				if (value < 1 || value > MAX_DICE) {
					throw new ApiException(400, "DICE_COUNT_INVALID",
						"Cada grupo rola de 1 a " + MAX_DICE + " dados.");
				}
				return value;
			}
			catch (NumberFormatException error) {
				throw new ApiException(400, "DICE_EXPRESSION_INVALID", "Quantidade de dados invalida.");
			}
		}

		private static int parseFaces(String raw) {
			if (raw.isEmpty()) {
				throw new ApiException(400, "DICE_EXPRESSION_INVALID", "Grupo sem faces.");
			}
			try {
				int value = Integer.parseInt(raw);
				if (value < 1 || value > MAX_FACES) {
					throw new ApiException(400, "DICE_FACES_INVALID",
						"O dado vai de 1 a " + MAX_FACES + " faces.");
				}
				return value;
			}
			catch (NumberFormatException error) {
				throw new ApiException(400, "DICE_EXPRESSION_INVALID", "Faces invalidas.");
			}
		}
	}
}
