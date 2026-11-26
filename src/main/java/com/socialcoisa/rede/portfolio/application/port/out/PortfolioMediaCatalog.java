package com.socialcoisa.rede.portfolio.application.port.out;

import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;

/**
 * Confere que uma mídia referenciada por um bloco é do dono e tem o tipo que o bloco espera.
 *
 * <p>A checagem de conteúdo (NSFW) é da fase de mídia, junto com o envio; aqui a decisão é só
 * sobre a referência: a mídia existe, pronta, do dono e com o mime certo para o tipo do bloco.</p>
 */
public interface PortfolioMediaCatalog {

	/** {@code true} quando a mídia existe, está pronta, é do dono e cabe no tipo do bloco. */
	boolean usable(String userId, String mediaId, PortfolioBlockType type);
}
