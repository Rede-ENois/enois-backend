package com.socialcoisa.rede.media.application.port.out;

import java.util.List;

/**
 * Páginas em que uma mídia se apoia para poder ficar no ar.
 *
 * <p>Para imagem é a própria mídia. Para PDF são as páginas RENDERIZADAS de cada folha: o arquivo
 * em si nunca é olhado pelo classificador, então o que decide são as imagens das páginas.</p>
 */
public interface MediaPageCatalog {

	/**
	 * Vazia enquanto o PDF não tem página renderizada (pedido pendente ou que falhou): sem página
	 * não há o que mostrar nem o que aprovar. Página que já foi removida do storage não conta como
	 * página — o documento que perdeu folha no takedown não pode continuar publicável.
	 */
	List<PageRef> pagesOf(String mediaId);

	record PageRef(String mediaId, String mimeType) {
	}
}
