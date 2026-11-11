package com.socialcoisa.rede.jobs.adapters.out.net;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.jobs.application.port.out.ResumeFetcher;
import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

/**
 * Baixa o currículo do CDN para anexar ao e-mail de candidatura.
 *
 * <p>O endereço vem do perfil da pessoa, então é dado que ela escolheu: passa pelo
 * {@link OutboundUrlGuard} antes de qualquer conexão (nada de buscar `169.254.169.254` ou um
 * serviço interno porque alguém colou esse link no campo de currículo). Caminho relativo antigo
 * (`/uploads/...`) é resolvido contra o endpoint do ImageKit, como o resto da mídia faz.</p>
 *
 * <p>O teto de bytes é o do anexo de e-mail: um PDF de currículo cabe com folga, e um arquivo
 * maior que isso não é currículo — é engano ou abuso, e o envio segue sem anexo.</p>
 */
@Component
class HttpResumeFetcher implements ResumeFetcher {

	private static final Logger LOG = LoggerFactory.getLogger(HttpResumeFetcher.class);

	/** Teto do anexo: 10 MB. Acima disso o provedor de e-mail recusaria a mensagem inteira. */
	private static final int MAX_BYTES = 10 * 1024 * 1024;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final OutboundUrlGuard urlGuard;
	private final String imageKitEndpoint;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		// Sem redirects: um 302 para a rede interna burlaria a checagem do host de origem.
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	HttpResumeFetcher(OutboundUrlGuard urlGuard,
			@Value("${rede.media.imagekit.url-endpoint:}") String imageKitEndpoint) {
		this.urlGuard = urlGuard;
		this.imageKitEndpoint = imageKitEndpoint == null ? "" : imageKitEndpoint.trim();
	}

	@Override
	public Optional<FetchedResume> fetch(String url) {
		String absolute = absolute(url);
		if (absolute == null) {
			return Optional.empty();
		}
		Optional<OutboundUrlGuard.CheckedUrl> checked = urlGuard.checked(absolute);
		if (checked.isEmpty()) {
			LOG.debug("curriculo_recusado: destino nao permitido");
			return Optional.empty();
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(checked.get().uri())
				.timeout(TIMEOUT)
				.header("User-Agent", "enois/1.0 (+https://enois.ia.br)")
				.header("Accept", "application/pdf,application/msword,"
					+ "application/vnd.openxmlformats-officedocument.wordprocessingml.document,*/*;q=0.5")
				.GET()
				.build();
			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				response.body().close();
				LOG.debug("curriculo_indisponivel: CDN respondeu {}", response.statusCode());
				return Optional.empty();
			}
			String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
			try (InputStream body = response.body()) {
				byte[] bytes = body.readNBytes(MAX_BYTES + 1);
				if (bytes.length == 0 || bytes.length > MAX_BYTES) {
					LOG.debug("curriculo_recusado: tamanho fora do limite");
					return Optional.empty();
				}
				return Optional.of(new FetchedResume(bytes, fileName(absolute, contentType),
					contentType.isBlank() ? null : contentType));
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			LOG.debug("curriculo_download_falhou: {}", failure.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Caminho relativo antigo vira endereço absoluto do CDN; o resto passa como veio. */
	String absolute(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String clean = url.trim().replace("%2F", "/").replace("%2f", "/");
		if (!clean.startsWith("/uploads/")) {
			return clean;
		}
		String base = imageKitEndpoint.isEmpty() ? "https://ik.imagekit.io/socialcoisa"
			: (imageKitEndpoint.endsWith("/") ? imageKitEndpoint.substring(0, imageKitEndpoint.length() - 1)
				: imageKitEndpoint);
		return base + clean;
	}

	/**
	 * Nome do anexo: o último segmento do caminho, com extensão coerente com o tipo.
	 *
	 * O nome importa para quem recebe — "curriculo.pdf" é o que a pessoa reconhece na caixa de
	 * entrada. Sem extensão no caminho, ela vem do content-type.
	 */
	static String fileName(String url, String contentType) {
		String path = url;
		try {
			path = URI.create(url).getPath() == null ? url : URI.create(url).getPath();
		}
		catch (RuntimeException ignored) {
			// URL estranha: cai no nome padrão abaixo.
		}
		String last = path.substring(path.lastIndexOf('/') + 1);
		if (last.isBlank() || last.contains(".") == false) {
			String extension = switch (contentType == null ? "" : contentType) {
				case "application/pdf" -> ".pdf";
				case "application/msword" -> ".doc";
				case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
				default -> "";
			};
			return (last.isBlank() ? "curriculo" : last) + extension;
		}
		return last;
	}
}
