package com.socialcoisa.rede.linkpreview.application;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.linkpreview.domain.LinkPreview;

/**
 * A miniatura de um link publicado: pagina do proprio Énóis ou site de fora.
 *
 * <p>A ordem importa. Primeiro o endereco e comparado com o endereco publico do site: se for uma
 * pagina da casa, o resumo vem do banco e NADA sai para a internet. So o que sobra e lido como
 * site externo, e essa leitura passa pela guarda de destino (nunca a rede interna).</p>
 *
 * <p>O cache mora em {@link LinkPreviewCache}: uma chave para o site de fora (igual para todos) e
 * outra para a pagina da casa (que depende de quem le).</p>
 */
@Service
public class LinkPreviewService {

	private final LinkPreviewCache cache;
	private final String siteUrl;

	public LinkPreviewService(LinkPreviewCache cache,
			@Value("${rede.site-url:https://enois.ia.br}") String siteUrl) {
		this.cache = cache;
		this.siteUrl = siteUrl;
	}

	/**
	 * A miniatura do endereco, ou vazio quando ele nao tem nada para mostrar.
	 *
	 * <p>{@code viewerId} so influencia pagina interna (post de canal privado, jogo nao publicado).
	 * A leitura anonima vem primeiro: e a que serve a quase todo mundo e a que o cache guarda para
	 * todos. A leitura com sessao so acontece quando a anonima nao achou nada, e o vazio dessa
	 * segunda leitura nao e guardado.</p>
	 */
	public Optional<LinkPreview> previewOf(String url, String viewerId) {
		if (url == null || url.isBlank()) {
			return Optional.empty();
		}
		String path = internalPath(url);
		if (path == null) {
			return Optional.ofNullable(cache.external(url));
		}
		LinkPreview publica = cache.internalPublic(path);
		if (publica != null || viewerId == null) {
			return Optional.ofNullable(publica);
		}
		LinkPreview pessoal = cache.internalForViewer(path, viewerId);
		if (pessoal != null) {
			return Optional.of(pessoal);
		}
		// Endereco do site que nao e uma pagina conhecida, nao existe mais ou nao e visivel para
		// quem le: nao ha o que buscar fora, porque o destino e a propria casa.
		return Optional.empty();
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
