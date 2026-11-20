package com.socialcoisa.rede.doacoes.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o texto do extrato Pix (o que a pessoa selecionou e colou no formulário) e devolve as
 * transações de ENTRADA.
 *
 * <p>O extrato vem em blocos: a data numa linha, o valor em outra, o identificador em outra. Por
 * isso a leitura é por REGISTRO (tudo que começa numa linha com data e vai até a próxima), não por
 * linha solta.</p>
 *
 * <p>Quem recebe tem uma entrada, e é essa a regra que se conhece com certeza. O que vem depois é
 * heurística conservadora: na dúvida, a linha fica de fora e só entra pelo fluxo em que a doação já
 * aparece aberta no extrato do recebedor.</p>
 * <ul>
 * <li>registro com sinal de saída ({@code -R$ 10,00}) é saída, não doação;</li>
 * <li>valor com {@code +} é entrada; valor sem sinal só vira entrada se o registro também trouxer
 * uma marcação de recebimento;</li>
 * <li>o registro precisa do identificador, da descrição ou de uma dessas marcações;</li>
 * <li>boleto, TED, DOC, saque, devolução e estorno ficam de fora.</li>
 * </ul>
 */
public final class PixStatement {

	/** Uma entrada que o extrato mostrou. */
	public record Entry(String detail, long amountCents, String description, String date, String raw) {
	}

	private static final Pattern AMOUNT = Pattern.compile(
		"(\\+|\\u2212|-)?\\s*R\\$\\s*(\\d{1,3}(?:\\.\\d{3})*|\\d+),(\\d{2})");
	private static final Pattern BARE_AMOUNT = Pattern.compile(
		"(\\+|\\u2212|-)?\\s*(\\d{1,3}(?:\\.\\d{3})*|\\d+),(\\d{2})(?!\\d)");
	private static final Pattern DETAIL = Pattern.compile(
		"(?i)(?:\\bID\\s*[:#]?\\s*|\\bID\\s*fim\\s*a\\s*fim\\s*[:#]?\\s*)([A-Za-z0-9]{20,40})");
	private static final Pattern DATE = Pattern.compile("\\b(\\d{2}/\\d{2}(?:/\\d{2,4})?)\\b");
	private static final Pattern RECEIVED_WORD = Pattern.compile(
		"(?i)\\b(recebid[oa]s?|recebeu|creditad[oa]s?|entrada)\\b");
	private static final Pattern CONFIRMATION = Pattern.compile(
		"(?i)(?:confirma[çc][ãa]o|situa[çc][ãa]o)\\s*[: ]\\s*([^;|]{3,120})");
	private static final Pattern NOT_RECEIVED = Pattern.compile(
		"(?i)\\b(boleto|pagamento|pago|pagou|enviad[oa]|enviou|devolu[çc][ãa]o|"
			+ "transfer[eê]ncia\\s+enviada|ted|doc|saque|d[eé]bito|estorno|devolvido)\\b");

	private PixStatement() {
	}

	public static List<Entry> received(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		List<Entry> entradas = new ArrayList<>();
		for (String registro : registros(text)) {
			Entry entry = read(registro);
			if (entry != null) {
				entradas.add(entry);
			}
		}
		return entradas;
	}

	/**
	 * As linhas do extrato formam um registro por transação. A primeira linha com data abre o
	 * registro; as seguintes pertencem a ele (o bloco costuma ser data, valor e identificador).
	 * Data no meio do bloco não é tratada como abertura: cada linha com data já começou um.
	 */
	private static List<String> registros(String text) {
		List<String> registros = new ArrayList<>();
		StringBuilder atual = new StringBuilder();
		for (String linha : text.split("\\r?\\n")) {
			if (linha == null || linha.isBlank()) {
				continue;
			}
			// Linha com data abre o registro.
			if (DATE.matcher(linha).find()) {
				registros.add(atual.toString());
				atual.setLength(0);
			}
			if (!atual.isEmpty()) {
				atual.append('\n');
			}
			atual.append(linha);
		}
		if (!atual.isEmpty()) {
			registros.add(atual.toString());
		}
		return registros;
	}

	private static Entry read(String registro) {
		String lower = registro.toLowerCase(Locale.ROOT);
		// Os verbos de saída vencem a marcação de recebimento: a linha de quem enviou também fala em
		// Pix ("Pix enviado"). E o identificador não salva a linha — o extrato de quem enviou também
		// o tem, e é justamente a fraude fácil de tentar.
		if (NOT_RECEIVED.matcher(lower).find()) {
			return null;
		}
		boolean recebimento = RECEIVED_WORD.matcher(lower).find();

		Matcher valor = AMOUNT.matcher(registro);
		if (!valor.find()) {
			valor = BARE_AMOUNT.matcher(registro);
			if (!valor.find()) {
				return null;
			}
		}
		String sinal = valor.group(1);
		if ("-".equals(sinal) || "\u2212".equals(sinal)) {
			return null;
		}
		boolean entradaMarcada = "+".equals(sinal);
		if (!entradaMarcada && !recebimento) {
			return null;
		}
		long cents = Long.parseLong(valor.group(2).replace(".", "")) * 100 + Long.parseLong(valor.group(3));
		if (cents <= 0) {
			return null;
		}

		Matcher id = DETAIL.matcher(registro);
		String detail = id.find() ? id.group(1) : null;
		Matcher confirmacao = CONFIRMATION.matcher(registro);
		String description = confirmacao.find() ? confirmacao.group(1).trim() : null;
		// O sinal de entrada já é a marcação; sem ele, o registro precisa dizer que é recebimento.
		if (detail == null && description == null && !recebimento && !entradaMarcada) {
			return null;
		}

		String date = null;
		Matcher data = DATE.matcher(registro);
		if (data.find()) {
			date = data.group(1);
		}
		return new Entry(detail, cents, description, date, registro.replace('\n', ' ').trim());
	}
}
