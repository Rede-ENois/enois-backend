package com.socialcoisa.rede.linkpreview.application;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.shared.application.CacheConfig;

/**
 * O cache da miniatura, separado do servico para que a chamada passe pelo proxy do Spring.
 *
 * <p>Sao tres chaves, porque as tres leituras custam coisas diferentes:</p>
 *
 * <ul>
 * <li><b>Site de fora</b> — a leitura e a mesma para todo mundo, entao a chave e so o endereco. Um
 * link publicado por dez pessoas e lido uma vez.</li>
 * <li><b>Pagina da casa, leitura anonima</b> — o que a pagina mostra para quem nao tem sessao. E o
 * que quase todo mundo ve, entao e a chave que mais acerta.</li>
 * <li><b>Pagina da casa, leitura com sessao</b> — so e consultada quando a leitura anonima nao
 * achou nada (post de canal privado, jogo nao publicado). A chave leva o leitor.</li>
 * </ul>
 *
 * <p>O "nao tem nada" NUNCA entra no cache: o metodo devolve {@code null} nesse caso e o
 * {@code unless} barra a gravacao. Guardar o vazio por leitor faria cada visitante de um post
 * privado pagar a consulta de novo — que e exatamente a carga que o cache existe para evitar — e
 * ainda deixaria o vazio preso por 30 min depois de o post virar publico.</p>
 */
@Component
public class LinkPreviewCache {

	private final InternalPageResolver internal;
	private final LinkPreviewReader external;

	public LinkPreviewCache(InternalPageResolver internal, LinkPreviewReader external) {
		this.internal = internal;
		this.external = external;
	}

	/** Site de fora: mesma resposta para qualquer leitor. */
	@Cacheable(cacheNames = CacheConfig.LINK_PREVIEWS, key = "'x:' + #url", unless = "#result == null")
	public LinkPreview external(String url) {
		return external.read(url).map(LinkPreview::external).orElse(null);
	}

	/** Pagina da casa como um visitante sem sessao a ve. */
	@Cacheable(cacheNames = CacheConfig.LINK_PREVIEWS, key = "'i:' + #path + '|anon'", unless = "#result == null")
	public LinkPreview internalPublic(String path) {
		return internal.resolve(path, null).orElse(null);
	}

	/** Pagina da casa como ESTE leitor a ve; so chamada quando a leitura anonima nao achou nada. */
	@Cacheable(cacheNames = CacheConfig.LINK_PREVIEWS, key = "'i:' + #path + '|' + #viewerId",
			unless = "#result == null")
	public LinkPreview internalForViewer(String path, String viewerId) {
		return internal.resolve(path, viewerId).orElse(null);
	}
}
