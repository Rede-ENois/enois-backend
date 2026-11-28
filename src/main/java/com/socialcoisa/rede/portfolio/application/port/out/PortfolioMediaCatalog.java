package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;

/**
 * Mídia que pode entrar numa peça do portfólio.
 *
 * <p>O bloco de mídia e o de PDF só aceitam arquivo do próprio dono, do tipo certo e que já passou
 * pela checagem de conteúdo. É a mesma regra da mídia no resto do produto: conteúdo sexual não fica
 * no ar, e o que ninguém conseguiu olhar (PDF que não renderizou, página ainda sem veredito) não
 * publica.</p>
 */
public interface PortfolioMediaCatalog {

	/** {@code true} quando a mídia é do dono, cabe no tipo do bloco e está aprovada pela checagem. */
	boolean usable(String userId, String mediaId, PortfolioBlockType type);

	/**
	 * O que falta para cada mídia poder ir ao ar: mídia → ids das páginas sem veredito (a própria
	 * mídia, quando não é PDF). Ausente do mapa = aprovada.
	 */
	Map<String, List<String>> uncheckedMedia(Collection<String> mediaIds);
}
