package com.socialcoisa.rede.network.adapters.in.web;

/**
 * Escritor de XML mínimo, onde <b>todo valor de dado passa por {@link #text(String)}</b>.
 *
 * <p><b>Por que existe:</b> o gateway S3 monta documentos XML com valores que vêm do cliente
 * (nome de bucket, prefixo, chave de objeto, continuação). Concatenar esses valores direto na
 * string é a definição de XSS/XML injection — e foi o que o CodeQL apontou no PR #914
 * ({@code java/xss}, linha do {@code body(...)}). A defesa não é "escapar em algum lugar": é não
 * existir caminho de escrita de dado que não passe pelo escape. Aqui não existe.</p>
 *
 * <p>A marcação (tags) vem de {@link #open(String)}/{@link #close(String)}, que só aceitam nomes de
 * elemento literais definidos no próprio gateway — nunca dado do cliente.</p>
 */
final class S3Xml {

	private final StringBuilder out = new StringBuilder(512);

	S3Xml() {
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
	}

	/** O namespace do S3: os SDKs o usam para desserializar; sem ele, o documento "não é S3". */
	private static final String S3_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";

	/** Abre um elemento. {@code name} é literal do gateway, jamais dado do cliente. */
	S3Xml open(String name) {
		out.append('<').append(name).append('>');
		return this;
	}

	/** Abre o elemento raiz com o namespace do S3 (literal, não dado). */
	S3Xml root(String name) {
		out.append('<').append(name).append(" xmlns=\"").append(S3_NAMESPACE).append("\">");
		return this;
	}

	S3Xml close(String name) {
		out.append("</").append(name).append('>');
		return this;
	}

	/** Elemento com texto. O valor é escapado AQUI, e é o único caminho para dado. */
	S3Xml element(String name, String value) {
		return open(name).text(value).close(name);
	}

	/** Elemento com número. */
	S3Xml element(String name, long value) {
		return open(name).text(Long.toString(value)).close(name);
	}

	/** Elemento com booleano no formato XML. */
	S3Xml element(String name, boolean value) {
		return open(name).text(value ? "true" : "false").close(name);
	}

	/** Texto de dado, escapado por construção. */
	S3Xml text(String value) {
		if (value == null) {
			return this;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&apos;");
				default -> {
					// Caractere de controle não é válido em XML 1.0; trocar por '?' preserva o
					// documento em vez de gerar um XML que o SDK não consegue ler.
					if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
						out.append('?');
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return this;
	}

	@Override
	public String toString() {
		return out.toString();
	}
}