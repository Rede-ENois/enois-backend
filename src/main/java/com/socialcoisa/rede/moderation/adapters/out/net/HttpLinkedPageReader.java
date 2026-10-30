package com.socialcoisa.rede.moderation.adapters.out.net;

import java.io.InputStream;
import java.net.InetAddress;
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

import com.socialcoisa.rede.moderation.application.port.out.LinkedPageReader;
import com.socialcoisa.rede.moderation.domain.PageSummary;

/**
 * Le a descricao de um destino publicado — com a regra de que o servidor nao visita a propria rede.
 *
 * <p>Quem publica escolhe o endereco, entao o endereco e dado NAO CONFIAVEL. Antes de cada salto
 * (o primeiro e cada redirecionamento) o host e resolvido e TODOS os enderecos precisam ser
 * publicos: rede interna, loopback, link-local, faixa de container e o endereco de metadados da
 * nuvem ficam de fora. Porta fora de 80/443 e esquema que nao seja `http(s)` tambem. Redirecionamento
 * e seguido na mao, com a mesma checagem em cada salto, e o teto e curto.</p>
 *
 * <p>A leitura para no primeiro trecho da pagina e no teto de bytes: interessa o titulo e a
 * descricao (inclusive os do Open Graph), nao o site inteiro. Nada do que foi lido e guardado ou
 * servido a alguem — so vai para a triagem, como dado.</p>
 */
@Component
public class HttpLinkedPageReader implements LinkedPageReader {

	private static final Logger LOG = LoggerFactory.getLogger(HttpLinkedPageReader.class);

	private static final int MAX_REDIRECTS = 3;

	private static final int MAX_BYTES = 64 * 1024;

	private static final Duration TIMEOUT = Duration.ofSeconds(6);

	private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern OG_TITLE = metaContent("og:title");

	private static final Pattern OG_DESCRIPTION = metaContent("og:description");

	private static final Pattern DESCRIPTION = metaContent("description");

	private static final Pattern OG_SITE = metaContent("og:site_name");

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@Override
	public Optional<PageSummary> summaryOf(URI url) {
		URI atual = url;
		for (int salto = 0; salto <= MAX_REDIRECTS && atual != null; salto++) {
			if (!isPublicDestination(atual)) {
				LOG.debug("link_destino_recusado host={}", atual.getHost());
				return Optional.empty();
			}
			try {
				HttpRequest request = HttpRequest.newBuilder(atual)
					.timeout(TIMEOUT)
					.header("User-Agent", "enois-moderacao/1.0 (+verificacao de link)")
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
				return Optional.of(summaryOf(atual, html));
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			catch (Exception falha) {
				LOG.debug("link_leitura_falhou host={} motivo={}", atual.getHost(), falha.getClass().getSimpleName());
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	/**
	 * O host resolve para algum endereco que nao deveria ser alcancavel de dentro do servidor?
	 *
	 * Todos os enderecos precisam ser publicos: um host que resolve para um IP publico e um interno
	 * ao mesmo tempo e tratado como interno.
	 */
	boolean isPublicDestination(URI url) {
		String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return false;
		}
		String host = url.getHost();
		if (host == null || host.isBlank()) {
			return false;
		}
		int port = url.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : url.getPort();
		if (port != 80 && port != 443) {
			return false;
		}
		try {
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (!isPublic(address)) {
					return false;
				}
			}
			return true;
		}
		catch (Exception naoResolve) {
			return false;
		}
	}

	private static boolean isPublic(InetAddress address) {
		if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
				|| address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			int primeiro = bytes[0] & 0xff;
			int segundo = bytes[1] & 0xff;
			// 0.0.0.0/8, 100.64.0.0/10 (operadora/CGNAT), 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4
			if (primeiro == 0 || (primeiro == 100 && segundo >= 64 && segundo <= 127)
					|| (primeiro == 192 && segundo == 0) || (primeiro == 198 && (segundo == 18 || segundo == 19))
					|| primeiro >= 240) {
				return false;
			}
			// 169.254.0.0/16 e o caminho classico para o metadado da nuvem.
			return !(primeiro == 169 && segundo == 254);
		}
		// IPv6: fc00::/7 (rede privada) e fe80::/10 (link-local) ja caem nos testes acima; o resto
		// que sobra de perigoso e o endereco de metadados, que e link-local.
		return true;
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

	/** Titulo e descricao: os do Open Graph primeiro (sao o resumo que o site publica), depois o HTML. */
	static PageSummary summaryOf(URI url, String html) {
		String ogTitle = first(OG_TITLE, html);
		String title = ogTitle != null ? ogTitle : first(TITLE, html);
		String description = first(OG_DESCRIPTION, html);
		if (description == null) {
			description = first(DESCRIPTION, html);
		}
		String site = first(OG_SITE, html);
		if (site != null && title != null && !title.toLowerCase(Locale.ROOT).contains(site.toLowerCase(Locale.ROOT))) {
			title = title + " (" + site + ")";
		}
		return new PageSummary(url.toString(), decode(title), decode(description));
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

}
