package com.socialcoisa.rede.emotes.adapters.out.images;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Baixa a imagem do CDN do provedor.
 *
 * Timeout curto de proposito: o `cdn.frankerfacez.com` tem picos em que nao responde em 12s. Sem
 * limite, cada quadrado vazio do seletor segurava uma thread do pool ate o CDN desistir.
 */
public class HttpImageFetcher implements ImageFetcher {

	private static final int MAX_BYTES = 1024 * 1024;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

	@Override
	public StoredImage fetch(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(6))
			.header("User-Agent", "enois/1.0 (+https://enois.ia.br)")
			.header("Accept", "image/*")
			.GET()
			.build();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() == 404 || response.statusCode() == 410) {
			throw new ImageNotFoundException("CDN de figurinha respondeu " + response.statusCode());
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("CDN de figurinha respondeu " + response.statusCode());
		}
		String contentType = response.headers()
			.firstValue("content-type")
			.orElse("")
			.toLowerCase(Locale.ROOT);
		if (!contentType.startsWith("image/")) {
			throw new IllegalStateException("CDN de figurinha respondeu " + contentType);
		}
		try (InputStream body = response.body()) {
			byte[] bytes = body.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES) {
				throw new IllegalStateException("Imagem de figurinha maior que o limite");
			}
			return new StoredImage(bytes, contentType);
		}
	}
}