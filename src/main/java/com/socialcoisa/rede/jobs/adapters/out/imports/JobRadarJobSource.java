package com.socialcoisa.rede.jobs.adapters.out.imports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;

/**
 * JobRadar como fonte de LISTAGEM + REDIRECIONAMENTO (sem credencial, sem scraping de JS).
 *
 * Usa o **sitemap publico** que o proprio JobRadar publica para indexadores
 * (`/sitemap-vagas.xml`, permitido no robots.txt — que so bloqueia URLs com `?`). Cada item vira uma
 * vaga externa com **titulo derivado do slug**, a **URL original** e o selo "Vaga externa". Nao
 * reproduzimos descricao/empresa/salario (ficam nulos) — a pessoa confere e se candidata no JobRadar.
 *
 * Nao usamos a API privada da SPA (`/api/*`, exige request token) nem a API key do `/v1`: isso seria
 * contornar controle de acesso. Se o JobRadar conceder acesso oficial, um adaptador por API entra
 * depois usando a mesma porta.
 */
@Component
public class JobRadarJobSource implements JobImportSource {

	private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
	private static final Pattern SLUG = Pattern.compile("/vaga/(.+?)-(\\d+)$");

	private final String sitemapUrl;
	private final String userAgent;
	private final boolean enabled;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public JobRadarJobSource(
			@Value("${rede.jobs.import.jobradar.sitemap-url:https://jobradar.com.br/sitemap-vagas.xml}") String sitemapUrl,
			@Value("${rede.jobs.import.jobradar.user-agent:EnoisBot/1.0 (+https://enois.ia.br/developers)}") String userAgent,
			@Value("${rede.jobs.import.jobradar.enabled:true}") boolean enabled) {
		this.sitemapUrl = sitemapUrl;
		this.userAgent = userAgent;
		this.enabled = enabled;
	}

	@Override
	public String name() {
		return "JobRadar";
	}

	/** Ligada por configuracao (e o sitemap e publico). Em teste, desligamos para nao ir a rede. */
	@Override
	public boolean available() {
		return enabled;
	}

	@Override
	public List<ExternalJob> discover(int limit) {
		// limit <= 0 = TODAS as vagas do sitemap (o requisito e carregar tudo).
		int capped = limit <= 0 ? Integer.MAX_VALUE : limit;
		String body;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(sitemapUrl))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", userAgent)
				.header("Accept", "application/xml")
				.GET()
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Nao foi possivel ler o sitemap do JobRadar.");
			}
			body = response.body();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Consulta ao JobRadar interrompida.");
		}
		catch (ApiException apiException) {
			throw apiException;
		}
		catch (Exception failure) {
			throw new ApiException(502, "IMPORT_SOURCE_ERROR", "Falha ao consultar o JobRadar.");
		}
		return parse(body, capped);
	}

	/** Extrai as vagas do XML do sitemap: so titulo (do slug), URL e id. Nada e inventado. */
	List<ExternalJob> parse(String sitemapXml, int limit) {
		List<ExternalJob> jobs = new ArrayList<>();
		if (!StringUtils.hasText(sitemapXml)) {
			return jobs;
		}
		Matcher matcher = LOC.matcher(sitemapXml);
		while (matcher.find() && jobs.size() < limit) {
			String url = matcher.group(1);
			Matcher slug = SLUG.matcher(url);
			if (!slug.find()) {
				continue;
			}
			String title = humanize(slug.group(1));
			String externalId = slug.group(2);
			jobs.add(new ExternalJob(externalId, title, null, null, null, null, null, url, url, name(), null));
		}
		return jobs;
	}

	/** Conectivos que ficam minusculos no meio do titulo. */
	private static final java.util.Set<String> LOWERCASE = java.util.Set.of(
		"de", "da", "do", "das", "dos", "e", "em", "para", "com", "a", "o", "na", "no");

	/** Ruido do slug que nao agrega ao titulo. */
	private static final java.util.Set<String> NOISE = java.util.Set.of("edtech", "vaga", "vagas");

	/**
	 * Limpa o titulo a partir do slug. O slug nao tem separador confiavel entre titulo e empresa, e
	 * empresa/local nao sao recuperaveis com seguranca — por isso NAO inventamos esses campos (ficam
	 * nulos). O que fazemos e deixar o TITULO apresentavel:
	 *  - "-a-"/"-o-" (inclusivo) vira "(a)"/"(o)" colado na palavra anterior;
	 *  - remove ruido (edtech/vaga) e palavras adjacentes duplicadas ("vitru vitru" -> "vitru");
	 *  - capitaliza mantendo conectivos minusculos.
	 */
	static String humanize(String slug) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String raw : slug.split("-")) {
			String word = raw.trim();
			if (word.isEmpty() || NOISE.contains(word.toLowerCase(java.util.Locale.ROOT))) {
				continue;
			}
			// "(a)"/"(o)" inclusivo: nao vira palavra propria, gruda na anterior.
			String previous = out.isEmpty() ? null : out.get(out.size() - 1);
			if (previous != null && !previous.endsWith(")")
					&& (word.equalsIgnoreCase("a") || word.equalsIgnoreCase("o"))) {
				out.set(out.size() - 1, previous + " (" + word.toLowerCase(java.util.Locale.ROOT) + ")");
				continue;
			}
			// Palavra igual a anterior (a base, sem o "(a)"): descarta a repeticao.
			if (previous != null && previous.split(" ")[0].equalsIgnoreCase(word)) {
				continue;
			}
			out.add(word);
		}
		if (out.isEmpty()) {
			return slug;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < out.size(); i++) {
			String word = out.get(i);
			boolean first = i == 0;
			String lower = word.toLowerCase(java.util.Locale.ROOT);
			if (first || !LOWERCASE.contains(lower)) {
				word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(word);
		}
		return sb.toString();
	}
}
