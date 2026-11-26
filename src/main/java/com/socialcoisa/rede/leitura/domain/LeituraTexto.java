package com.socialcoisa.rede.leitura.domain;

import java.util.List;

/**
 * O que a leitura entendeu de um texto: o(s) TEMA(S) de verdade (não a palavra mais repetida), a
 * INTENÇÃO e um resumo curto. É o que o Papo Reto usa para agrupar por assunto real e o que o
 * Wrapped usa para descrever o ano.
 *
 * <p>Quando a leitura vem do modelo, {@code lidoPeloModelo} é {@code true}; quando degrada para a
 * extração determinística, é {@code false} e a intenção é {@link Intencao#DESCONHECIDA}. O
 * consumidor decide o que fazer com essa diferença — o dado carrega a própria procedência.</p>
 *
 * @param temas   temas normalizados (minúsculos, sem acento), no máximo 3
 * @param resumo  uma frase curta do que o texto diz; {@code null} quando não houve leitura
 */
public record LeituraTexto(
		List<String> temas,
		Intencao intencao,
		String resumo,
		boolean lidoPeloModelo) {

	private static final int MAXIMO_TEMAS = 3;
	private static final int RESUMO_MAX = 280;

	public static LeituraTexto vazia() {
		return new LeituraTexto(List.of(), Intencao.DESCONHECIDA, null, false);
	}

	/**
	 * Constrói uma leitura já validada: temas aparados, sem duplicata, limitados a três e nunca
	 * vazios; resumo com tamanho limitado. A validação é do domínio, não do modelo — o que o
	 * modelo devolve passa por aqui antes de ser confiável.
	 */
	public static LeituraTexto validada(List<String> temas, Intencao intencao, String resumo,
			boolean doModelo) {
		List<String> limpos = temas == null ? List.of() : temas.stream()
				.filter(t -> t != null && !t.isBlank())
				.map(LeituraTexto::normalizarTema)
				.distinct()
				.limit(MAXIMO_TEMAS)
				.toList();
		String resumoLimpo = resumo == null ? null : resumo.trim();
		if (resumoLimpo != null && resumoLimpo.length() > RESUMO_MAX) {
			resumoLimpo = resumoLimpo.substring(0, RESUMO_MAX);
		}
		return new LeituraTexto(limpos, intencao == null ? Intencao.DESCONHECIDA : intencao, resumoLimpo,
				doModelo);
	}

	/** Tema normalizado como as falas do Papo Reto: sem acento, minúsculo, espaços simples. */
	public static String normalizarTema(String tema) {
		String semAcento = java.text.Normalizer.normalize(tema, java.text.Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "");
		return semAcento.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
	}

	public boolean temTemas() {
		return !temas.isEmpty();
	}
}
