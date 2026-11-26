package com.socialcoisa.rede.portfolio.domain;

/**
 * Tipo de peça do portfólio. Cada um carrega um campo diferente no bloco, e o serviço recusa
 * bloco com o campo errado em vez de gravar uma peça que a tela não consegue mostrar.
 *
 * <p>O carrossel de PDF e a exportação em PDF não dependem desta lista: a página HTML aceita
 * imagem, e o bloco de mídia é o mesmo de qualquer imagem.</p>
 */
public enum PortfolioBlockType {
	/** Card de projeto do perfil, referenciado por `project_id` (não copiado). */
	PROJECT,
	/** Texto curto em markdown renderizado pela tela. */
	TEXT,
	/** Imagem ou vídeo da galeria, referenciado por `media_id`. */
	MEDIA,
	/** Documento PDF hospedado em media, referenciado por `media_id`. */
	PDF,
	/** Link externo com título. */
	LINK
}
