package com.socialcoisa.rede.network.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code x-amz-meta-*} do objeto (brief §11, V367) — o que o cliente declarou no upload e espera
 * reencontrar no {@code HeadObject}.
 *
 * <p><b>Antes:</b> o gateway descartava os cabeçalhos em silêncio; o SDK recebia 200 e nunca mais via o
 * valor. <b>Agora:</b> ficam guardados como JSON no objeto e voltam como cabeçalho na leitura.</p>
 *
 * <h2>Regras — do S3 e do custo real</h2>
 *
 * <ul>
 *   <li><b>Até 10 pares</b>, nome até 40 caracteres, valor até 256, e o conjunto até 2 KB. O limite do
 *       S3 é 2 KB e o gateway não inventa folga: passar disso é {@code InvalidArgument}, nunca
 *       truncamento silencioso — cortar metadado de quem confia nele é pior que recusar.</li>
 *   <li><b>Nome fora do alfabeto do cabeçalho é recusado.</b> O nome volta em um cabeçalho HTTP, e um
 *       {@code \r\n} dentro dele seria injeção de resposta. Aceitar e sanitizar depois faria o cliente
 *       buscar um nome e achar outro.</li>
 *   <li><b>Chave em minúsculas.</b> O S3 normaliza {@code x-amz-meta-Autor} para {@code autor}, e o
 *       cabeçalho HTTP é case-insensitive de qualquer forma; guardar a forma do cliente faria
 *       {@code Autor} e {@code autor} serem duas chaves para a mesma coisa.</li>
 *   <li><b>Duplicata no mesmo pedido vira a primeira</b>: o {@code HttpHeaders} do Spring já junta
 *       valores repetidos numa linha, e a que vale é a primeira — como diz a especificação do
 *       cabeçalho.</li>
 * </ul>
 *
 * <p>Vazio é válido e é o caso comum: objeto sem metadado declarado não carrega cabeçalho nenhum.</p>
 */
public record ObjectMetadata(Map<String, String> values) {

	/** O limite do S3 para o conjunto inteiro de metadados, em bytes (soma de nome e valor). */
	public static final int MAX_BYTES = 2048;
	public static final int MAX_PAIRS = 10;
	public static final int MAX_NAME = 40;
	public static final int MAX_VALUE = 256;

	/** O alfabeto que pode voltar como cabeçalho HTTP sem quebrar nada. */
	private static final java.util.regex.Pattern NOME = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,40}");

	/** Metadado nenhum — o formato de tudo que existia antes do V367. */
	public static final ObjectMetadata EMPTY = new ObjectMetadata(Map.of());

	public ObjectMetadata {
		values = values == null ? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Valida e normaliza o que veio do pedido. Lança {@link IllegalArgumentException} com a mensagem que
	 * vai para o cliente — a rota a converte em {@code InvalidArgument}.
	 */
	public static ObjectMetadata of(Map<String, String> brutos) {
		if (brutos == null || brutos.isEmpty()) {
			return EMPTY;
		}
		Map<String, String> normalizado = new LinkedHashMap<>();
		int bytes = 0;
		for (Map.Entry<String, String> entrada : brutos.entrySet()) {
			String nome = entrada.getKey() == null ? "" : entrada.getKey().trim().toLowerCase(Locale.ROOT);
			String valor = entrada.getValue() == null ? "" : entrada.getValue();
			if (!NOME.matcher(nome).matches()) {
				throw new IllegalArgumentException(
					"Nome de metadado invalido: so letras, numeros, ponto, hifen e sublinhado, ate 40.");
			}
			if (valor.length() > MAX_VALUE) {
				throw new IllegalArgumentException("Valor de metadado acima de " + MAX_VALUE + " caracteres.");
			}
			bytes += nome.length() + valor.length();
			// A primeira ocorrência vence; a repetição do mesmo nome no pedido é a mesma chave.
			normalizado.putIfAbsent(nome, valor);
		}
		if (normalizado.size() > MAX_PAIRS) {
			throw new IllegalArgumentException("Metadados demais: o limite e " + MAX_PAIRS + " pares.");
		}
		if (bytes > MAX_BYTES) {
			throw new IllegalArgumentException("Metadados acima de " + MAX_BYTES + " bytes no total.");
		}
		return new ObjectMetadata(normalizado);
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	/** JSON para a coluna — o formato é este e só este; a leitura usa {@link #fromJson(String)}. */
	public String toJson() {
		if (values.isEmpty()) {
			return "{}";
		}
		StringBuilder json = new StringBuilder("{");
		boolean primeiro = true;
		for (Map.Entry<String, String> entrada : values.entrySet()) {
			if (!primeiro) {
				json.append(',');
			}
			primeiro = false;
			json.append('"').append(escapar(entrada.getKey())).append("\":\"").append(escapar(entrada.getValue()))
				.append('"');
		}
		return json.append('}').toString();
	}

	/**
	 * Lê o JSON da coluna. JSON inválido é <b>metadado vazio</b>, não erro de leitura: o corpo do objeto
	 * continua servível, e um metadado corrompido não pode tirar o arquivo do ar.
	 */
	public static ObjectMetadata fromJson(String json) {
		if (json == null || json.isBlank() || "{}".equals(json.trim())) {
			return EMPTY;
		}
		Map<String, String> lido = new LinkedHashMap<>();
		String texto = json.trim();
		if (!texto.startsWith("{") || !texto.endsWith("}")) {
			return EMPTY;
		}
		int i = 1;
		while (i < texto.length() - 1) {
			while (i < texto.length() - 1 && (texto.charAt(i) == ',' || Character.isWhitespace(texto.charAt(i)))) {
				i++;
			}
			if (i >= texto.length() - 1) {
				break;
			}
			String[] chave = lerString(texto, i);
			if (chave == null) {
				return EMPTY;
			}
			i = Integer.parseInt(chave[1]);
			if (i >= texto.length() || texto.charAt(i) != ':') {
				return EMPTY;
			}
			String[] valor = lerString(texto, i + 1);
			if (valor == null) {
				return EMPTY;
			}
			i = Integer.parseInt(valor[1]);
			lido.put(chave[0], valor[0]);
		}
		return new ObjectMetadata(lido);
	}

	/** Lê uma string JSON a partir de {@code inicio}; devolve {valor, posição depois das aspas}. */
	private static String[] lerString(String texto, int inicio) {
		if (inicio >= texto.length() || texto.charAt(inicio) != '"') {
			return null;
		}
		StringBuilder valor = new StringBuilder();
		int i = inicio + 1;
		while (i < texto.length()) {
			char c = texto.charAt(i);
			if (c == '\\' && i + 1 < texto.length()) {
				char esc = texto.charAt(i + 1);
				valor.append(switch (esc) {
					case 'n' -> '\n';
					case 'r' -> '\r';
					case 't' -> '\t';
					case 'b' -> '\b';
					case 'f' -> '\f';
					default -> esc;
				});
				i += 2;
				continue;
			}
			if (c == '"') {
				return new String[] { valor.toString(), String.valueOf(i + 1) };
			}
			valor.append(c);
			i++;
		}
		return null;
	}

	private static String escapar(String valor) {
		StringBuilder saida = new StringBuilder(valor.length() + 8);
		for (int i = 0; i < valor.length(); i++) {
			char c = valor.charAt(i);
			switch (c) {
				case '"' -> saida.append("\\\"");
				case '\\' -> saida.append("\\\\");
				case '\n' -> saida.append("\\n");
				case '\r' -> saida.append("\\r");
				case '\t' -> saida.append("\\t");
				default -> saida.append(c);
			}
		}
		return saida.toString();
	}
}