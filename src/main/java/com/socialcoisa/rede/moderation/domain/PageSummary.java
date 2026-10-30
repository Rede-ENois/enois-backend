package com.socialcoisa.rede.moderation.domain;

/**
 * O que uma pagina externa diz sobre si mesma: titulo e descricao, nada mais.
 *
 * E o suficiente para julgar o DESTINO de um link sem guardar o conteudo de terceiro: se o video
 * do YouTube e pornografico, o titulo e a descricao da propria pagina ja dizem isso. O corpo da
 * pagina nao e copiado, nao e guardado e nao vira conteudo nosso.
 */
public record PageSummary(String url, String title, String description) {

	private static final int MAX_TITULO = 200;

	private static final int MAX_DESCRICAO = 400;

	public PageSummary {
		title = truncate(title, MAX_TITULO);
		description = truncate(description, MAX_DESCRICAO);
	}

	public boolean hasAnything() {
		return (title != null && !title.isBlank()) || (description != null && !description.isBlank());
	}

	/** Texto que vai para a triagem. Vazio quando a pagina nao se descreve. */
	public String asPromptLine() {
		StringBuilder linha = new StringBuilder("- ").append(url);
		if (title != null && !title.isBlank()) {
			linha.append(" | titulo: ").append(title);
		}
		if (description != null && !description.isBlank()) {
			linha.append(" | descricao: ").append(description);
		}
		return linha.toString();
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		String limpo = value.replaceAll("\\s+", " ").trim();
		return limpo.length() <= max ? limpo : limpo.substring(0, max);
	}

}
