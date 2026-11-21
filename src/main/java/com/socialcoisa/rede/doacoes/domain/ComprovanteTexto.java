package com.socialcoisa.rede.doacoes.domain;

/**
 * Lê a linha digitada a mão no pedido de confirmação: o valor (obrigatório) e, se veio, o
 * identificador e a data. É o caminho de quem não quer (ou não consegue) colar o extrato.
 *
 * <pre>R$ 25,00 · 13/11/2026 · E23146975202611131200abcdefghijk</pre>
 *
 * A separação é por {@code ;} ou {@code |} (vírgula é da moeda, não dá para usar de separador).
 * Sem identificador o pedido fica sem E2E: vale pelo valor e pela data, que é conferido contra o
 * extrato.
 */
public final class ComprovanteTexto {

	public record Parsed(long amountCents, java.util.List<String> ids, String date) {
		public Parsed {
			ids = ids == null ? java.util.List.of() : java.util.List.copyOf(ids);
		}
	}

	private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9]{32,}");

	private ComprovanteTexto() {
	}

	public static Parsed parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String linha = raw.trim();
		String[] partes = linha.split("\\s*[;|]\\s*");
		Long cents = null;
		String e2e = null;
		String date = null;
		for (String parte : partes) {
			Long valor = valor(parte);
			if (valor != null) {
				cents = valor;
				continue;
			}
			String id = EndToEndId.fromReceipt(parte);
			if (id != null) {
				e2e = id;
				continue;
			}
			String f = EndToEndId.fromStatement(parte);
			if (f != null) {
				e2e = e2e == null ? f : e2e;
				continue;
			}
			String d = data(parte);
			if (d != null && date == null) {
				date = d;
			}
		}
		if (e2e == null) {
			// O identificador pode vir colado no texto ("… Pix recebido R$ 15,00 ID: E2…"), sem
			// separador: o maior token alfanumérico da linha que tem cara de E2E é o candidato.
			java.util.regex.Matcher token = TOKEN.matcher(linha);
			String candidato = null;
			while (token.find()) {
				String t = token.group();
				if (EndToEndId.fromReceipt(t) != null && (candidato == null || t.length() > candidato.length())) {
					candidato = t;
				}
			}
			e2e = candidato;
		}
		return cents == null ? null : new Parsed(cents, e2e == null ? java.util.List.of() : java.util.List.of(e2e), date);
	}

	private static Long valor(String parte) {
		var m = java.util.regex.Pattern
			.compile("R\\$\\s*(\\d{1,3}(?:\\.\\d{3})*|\\d+)(?:,(\\d{2}))?")
			.matcher(parte);
		if (!m.find()) {
			return null;
		}
		long reais = Long.parseLong(m.group(1).replace(".", ""));
		String centavos = m.group(2) == null ? "0" : m.group(2);
		return reais * 100 + Long.parseLong(centavos);
	}

	private static String data(String parte) {
		var m = java.util.regex.Pattern.compile("\\b(\\d{2})/(\\d{2})/(\\d{2,4})\\b").matcher(parte);
		if (!m.find()) {
			return null;
		}
		String ano = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
		return m.group(1) + "/" + m.group(2) + "/" + ano;
	}
}
