package com.socialcoisa.rede.linkpreview.application;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.shared.application.CacheConfig;

/**
 * A miniatura de um link publicado: pagina do proprio Énóis ou site de fora.
 *
 * <p>A ordem importa. Primeiro o endereco e comparado com o endereco publico do site: se for uma
 * pagina da casa, o resumo vem do banco e NADA sai para a internet. So o que sobra e lido como
 * site externo, e essa leitura passa pela guarda de destino (nunca a rede interna).</p>
 *
 * <p>O resultado fica em cache pela chave do endereco: o mesmo link aparece em varios posts e em
 * varios leitores, e a leitura externa e a parte cara. O cache guarda tambem o "nao tem nada" —
 * site que nao responde nao e buscado de novo a cada leitura.</p>
 */
@Service
public class LinkPreviewService {

	private final InternalPageResolver internal;
	private final LinkPreviewReader external;
	private final String siteUrl;

	public LinkPreviewService(InternalPageResolver internal, LinkPreviewReader external,
			@Value("${rede.site-url:https://enois.ia.br}") String siteUrl) {
		this.internal = internal;
		this.external = external;
		this.siteUrl = siteUrl;
	}

	/**
	 * A miniatura do endereco, ou vazio quando ele nao tem nada para mostrar.
	 *
	 * <p>{@code viewerId} so influencia pagina interna (post de canal privado). O cache e por
	 * endereco e por leitor: um post privado nao pode ser servido do cache para quem nao e membro.</p>
	 */
	@Cacheable(cacheNames = CacheConfig.LINK_PREVIEWS, key = "#url + '|' + (#viewerId == null ? 'anon' : #viewerId)")
	public Optional<LinkPreview> previewOf(String url, String viewerId) {
		if (url == null || url.isBlank()) {
			return Optional.empty();
		}
		String path = internalPath(url);
		if (path != null) {
			Optional<LinkPreview> page = internal.resolve(path, viewerId);
			if (page.isPresent()) {
				return page;
			}
			// Endereco do site que nao e uma pagina conhecida (ou que nao existe mais): nao ha o que
			// buscar fora, porque o destino e a propria casa.
			return Optional.empty();
		}
		return external.read(url).map(LinkPreview::external);
	}

	/**
	 * O caminho da pagina interna quando o endereco aponta para este site; nulo para site de fora.
	 *
	 * <p>Compara host e esquema, nao o texto: `https://enois.ia.br.evil.com/post/x` tem o nosso
	 * endereco como prefixo e NAO e nosso. Caminho relativo (`/post/x`) tambem e da casa.</p>
	 */
	String internalPath(String url) {
		String trimmed = url.trim();
		if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
			return trimmed;
		}
		try {
			URI uri = URI.create(trimmed);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return null;
			}
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (host.isEmpty() || !isOwnHost(host)) {
				return null;
			}
			String path = uri.getRawPath();
			return path == null || path.isBlank() ? "/" : path;
		}
		catch (RuntimeException invalido) {
			return null;
		}
	}

	private boolean isOwnHost(String host) {
		String own = hostOf(siteUrl);
		if (own == null) {
			return false;
		}
		if (host.equals(own)) {
			return true;
		}
		// `www.` e o mesmo site; subdominio qualquer NAO e (o site publica em um host so).
		return host.equals("www." + own) || own.equals("www." + host);
	}

	private static String hostOf(String url) {
		try {
			String host = URI.create(url.trim()).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		}
		catch (RuntimeException invalido) {
			return null;
		}
	}
}
