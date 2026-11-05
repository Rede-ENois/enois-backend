package com.socialcoisa.rede.linkpreview.adapters.out.net;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

/**
 * Le a miniatura de um site de fora a partir do que ele publica para compartilhamento.
 *
 * <p>O endereco vem de quem escreveu o post, entao e dado NAO CONFIAVEL. Antes de cada salto (o
 * primeiro e cada redirecionamento) o destino passa pelo {@link OutboundUrlGuard}: rede interna,
 * loopback, link-local, faixa de container e o endereco de metadados da nuvem ficam de fora, e
 * porta fora de 80/443 tambem. Redirecionamento e seguido na mao, com a mesma checagem em cada
 * salto, e o teto e curto.</p>
 *
 * <p>A leitura para no primeiro trecho da pagina e no teto de bytes: interessa o cabecalho, nao o
 * site inteiro. Nada do corpo e guardado — so os campos que o site declara sobre si.</p>
 */
@Component
public class HttpLinkPreviewReader implements LinkPreviewReader {

	private static final Logger LOG = LoggerFactory.getLogger(HttpLinkPreviewReader.class);

	private static final int MAX_REDIRECTS = 3;

	private static final int MAX_BYTES = 96 * 1024;

	private static final Duration TIMEOUT = Duration.ofSeconds(6);

	private static final Pattern OG_TITLE = metaContent("og:title");

	private static final Pattern OG_DESCRIPTION = metaContent("og:description");

	private static final Pattern OG_IMAGE = metaContent("og:image");

	private static final Pattern OG_IMAGE_SECURE = metaContent("og:image:secure_url");

	private static final Pattern OG_SITE = metaContent("og:site_name");

	private static final Pattern TWITTER_IMAGE = metaContent("twitter:image");

	private static final Pattern DESCRIPTION = metaContent("description");

	private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private final OutboundUrlGuard guard;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	public HttpLinkPreviewReader(OutboundUrlGuard guard) {
		this.guard = guard;
	}

	@Override
	public Optional<ExternalLinkPreview> read(String url) {
		if (url == null || url.isBlank()) {
			return Optional.empty();
		}
		URI atual;
		try {
			atual = URI.create(url.trim());
		}
		catch (RuntimeException invalido) {
			return Optional.empty();
		}
		for (int salto = 0; salto <= MAX_REDIRECTS && atual != null; salto++) {
			if (!guard.safe(atual.toString())) {
				LOG.debug("preview_destino_recusado host={}", atual.getHost());
				return Optional.empty();
			}
			try {
				HttpRequest request = HttpRequest.newBuilder(atual)
					.timeout(TIMEOUT)
					.header("User-Agent", "enois-preview/1.0 (+miniatura de link)")
					.header("Accept", "text/html,application/xhtml+xml")
					.header("Accept-Language", "pt-BR,pt;q=0.9")
					.GET()
					.build();
				HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status / 100 == 3) {
					String location = response.headers().firstValue("location").orElse(null);
					response.body().close();
					atual = location == null ? null : atual.resolve(location);
					continue;
				}
				if (status != 200) {
					response.body().close();
					return Optional.empty();
				}
				String contentType = response.headers().firstValue("content-type").orElse("");
				if (!contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
					response.body().close();
					return Optional.empty();
				}
				String html;
				try (InputStream body = response.body()) {
					html = readAtMost(body, charsetOf(contentType));
				}
				ExternalLinkPreview preview = parse(atual, html);
				return preview.hasAnything() ? Optional.of(preview) : Optional.empty();
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			catch (Exception falha) {
				LOG.debug("preview_leitura_falhou host={} motivo={}", atual.getHost(), falha.getClass().getSimpleName());
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	/**
	 * Extrai os campos do cabecalho. A imagem e resolvida contra o endereco final (o site pode
	 * publicar caminho relativo) e so sobrevive se for http(s) — o navegador de quem le e que a
	 * busca, entao um `javascript:` ou `data:` aqui viraria execucao na pagina.
	 */
	static ExternalLinkPreview parse(URI page, String html) {
		String title = first(OG_TITLE, html);
		if (title == null) {
			title = first(TITLE, html);
		}
		String description = first(OG_DESCRIPTION, html);
		if (description == null) {
			description = first(DESCRIPTION, html);
		}
		String image = first(OG_IMAGE_SECURE, html);
		if (image == null) {
			image = first(OG_IMAGE, html);
		}
		if (image == null) {
			image = first(TWITTER_IMAGE, html);
		}
		return new ExternalLinkPreview(page.toString(), decode(title), decode(description),
			absoluteImage(page, decode(image)), decode(first(OG_SITE, html)));
	}

	private static String absoluteImage(URI page, String image) {
		if (image == null || image.isBlank()) {
			return null;
		}
		try {
			URI resolved = page.resolve(image.trim());
			String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return null;
			}
			return resolved.toString();
		}
		catch (RuntimeException invalido) {
			return null;
		}
	}

	private static Pattern metaContent(String chave) {
		return Pattern.compile("<meta[^>]*(?:property|name)\\s*=\\s*[\"']" + Pattern.quote(chave)
				+ "[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']",
				Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	}

	private static String first(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String decode(String value) {
		if (value == null) {
			return null;
		}
		return value.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replace("&apos;", "'")
			.replace("&nbsp;", " ");
	}

	private static String readAtMost(InputStream body, Charset charset) throws java.io.IOException {
		byte[] buffer = new byte[8192];
		java.io.ByteArrayOutputStream lido = new java.io.ByteArrayOutputStream();
		int total = 0;
		int n;
		while (total < MAX_BYTES && (n = body.read(buffer)) > 0) {
			lido.write(buffer, 0, Math.min(n, MAX_BYTES - total));
			total += n;
		}
		return lido.toString(charset);
	}

	private static Charset charsetOf(String contentType) {
		for (String parte : contentType.split(";")) {
			String limpo = parte.trim().toLowerCase(Locale.ROOT);
			if (limpo.startsWith("charset=")) {
				try {
					return Charset.forName(limpo.substring("charset=".length()).replace("\"", "").trim());
				}
				catch (Exception desconhecido) {
					return StandardCharsets.UTF_8;
				}
			}
		}
		return StandardCharsets.UTF_8;
	}
}
