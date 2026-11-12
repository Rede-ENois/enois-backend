package com.socialcoisa.rede.jobs.domain;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Texto de descrição de vaga vindo de uma fonte externa.
 *
 * As fontes publicam HTML (Jobicy manda `jobDescription` com parágrafos e listas; Remotive manda
 * `description` em HTML). Guardar o HTML cru faria a tela renderizar marcação de terceiro, e guardar
 * nada faz a vaga parecer sem descrição quando a fonte publicou uma. Aqui o HTML vira texto legível:
 * quebras de bloco viram linha, o resto do texto é preservado como a fonte escreveu.
 */
public final class JobDescriptionText {

	private JobDescriptionText() {
	}

	/**
	 * Texto legível da descrição, ou nulo quando a fonte não publicou nada. Nada é inventado: sem
	 * conteúdo, o retorno é nulo e a tela diz que a fonte não publica a descrição.
	 */
	public static String fromHtml(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		// `wholeText` preserva as quebras que o HTML já tinha; as tags de bloco viram quebra explícita
		// para o texto não sair todo numa linha só.
		String withBreaks = raw.replaceAll("(?i)<br\\s*/?>", "\n")
			.replaceAll("(?i)</(p|div|li|h[1-6]|tr|ul|ol|blockquote)>", "\n");
		Document document = Jsoup.parse(withBreaks);
		document.select("script, style").remove();
		String text = document.body().wholeText()
			.replace('\u00a0', ' ')
			.replaceAll("[ \\t]+", " ")
			.replaceAll("\\n{3,}", "\n\n")
			.strip();
		return text.isEmpty() ? null : text;
	}
}
