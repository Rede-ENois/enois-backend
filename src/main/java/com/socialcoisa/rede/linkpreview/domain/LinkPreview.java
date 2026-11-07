package com.socialcoisa.rede.linkpreview.domain;

/**
 * A miniatura de um endereco publicado num post ou comentario.
 *
 * <p>Sao duas familias e o cartao mostra a mesma coisa nas duas: uma imagem (ou a ausencia dela),
 * um titulo, uma linha de apoio e o rotulo de onde o link leva. {@code kind = INTERNAL} e uma
 * pagina do proprio Énóis — nesse caso nada e buscado na internet: o resumo vem dos mesmos dados
 * que a pagina usa. {@code kind = EXTERNAL} e um site de fora, lido do que ele publica para
 * compartilhamento.</p>
 *
 * <p>{@code imageUrl} nulo e um resultado legitimo (pagina sem imagem): o cartao desenha o canto
 * com a marca, nunca uma imagem quebrada. {@code siteName} e o rotulo do canto do cartao — o
 * nome do site, ou o tipo de pagina interna ("Perfil", "Vaga"...).</p>
 */
public record LinkPreview(Kind kind, String url, String title, String description, String imageUrl, String siteName,
		boolean playable) {

	public enum Kind {
		INTERNAL, EXTERNAL
	}

	public static LinkPreview internal(String url, String title, String description, String imageUrl, String label) {
		return new LinkPreview(Kind.INTERNAL, url, title, description, imageUrl, label, false);
	}

	public static LinkPreview external(ExternalLinkPreview page) {
		return new LinkPreview(Kind.EXTERNAL, page.url(), page.title(), page.description(), page.imageUrl(),
			page.siteName(), false);
	}

	/** O link nao tem nada para mostrar: so o endereco fica visivel, sem cartao. */
	public static LinkPreview bare(String url) {
		return new LinkPreview(Kind.EXTERNAL, url, null, null, null, null, false);
	}

	public boolean hasCard() {
		return (title != null && !title.isBlank()) || (imageUrl != null && !imageUrl.isBlank())
			|| (description != null && !description.isBlank());
	}
}
