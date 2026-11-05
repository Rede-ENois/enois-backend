package com.socialcoisa.rede.linkpreview.domain;

/**
 * O que um site de fora diz sobre si mesmo: titulo, descricao, imagem e o nome do site.
 *
 * <p>E o que a miniatura do link mostra. Nada do corpo da pagina e copiado: so os campos que o
 * proprio site publica para ser compartilhado. Campo ausente fica nulo e o cartao se desenha sem
 * ele — nunca com texto inventado.</p>
 */
public record ExternalLinkPreview(String url, String title, String description, String imageUrl, String siteName) {

	private static final int MAX_TITULO = 200;

	private static final int MAX_DESCRICAO = 400;

	public ExternalLinkPreview {
		title = truncate(title, MAX_TITULO);
		description = truncate(description, MAX_DESCRICAO);
	}

	public boolean hasAnything() {
		return (title != null && !title.isBlank()) || (description != null && !description.isBlank())
			|| (imageUrl != null && !imageUrl.isBlank());
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		String limpo = value.replaceAll("\\s+", " ").trim();
		if (limpo.isEmpty()) {
			return null;
		}
		return limpo.length() <= max ? limpo : limpo.substring(0, max);
	}
}
