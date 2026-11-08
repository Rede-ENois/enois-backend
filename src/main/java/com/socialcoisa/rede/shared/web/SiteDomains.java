package com.socialcoisa.rede.shared.web;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Registrable domains that serve the same app (e.g. {@code enois.ia.br} and its mirror {@code isweee.com}).
 *
 * The first entry is the primary domain: configured URLs (OAuth callbacks, the front redirect) are
 * written for it. A browser flow that started on a mirror must come back to that mirror, because the
 * session cookies belong to the API host of the site the person is on ({@code api.<domain>}); landing
 * on another domain would look like a logout.
 */
@Component
public class SiteDomains {

	private final List<String> domains;

	public SiteDomains(@Value("${rede.site.domains:enois.ia.br}") String domains) {
		this.domains = Arrays.stream(domains.split(","))
			.map(d -> d.trim().toLowerCase(Locale.ROOT))
			.filter(StringUtils::hasText)
			.distinct()
			.toList();
		if (this.domains.isEmpty()) {
			throw new IllegalStateException("rede.site.domains needs at least one domain");
		}
	}

	public String primary() {
		return domains.get(0);
	}

	public List<String> all() {
		return domains;
	}

	/** The site an origin belongs to; anything unknown (no origin, QA, localhost) maps to the primary. */
	public String siteOf(String origin) {
		return hostOf(origin).flatMap(this::domainOfHost).orElse(primary());
	}

	/**
	 * Moves a URL written for the primary domain to {@code site}, keeping the subdomain
	 * ({@code api.enois.ia.br/x} becomes {@code api.isweee.com/x}). URLs on other hosts stay as they are.
	 */
	public String localize(String url, String site) {
		if (!StringUtils.hasText(url) || !StringUtils.hasText(site) || !domains.contains(site)
				|| site.equals(primary())) {
			return url;
		}
		Optional<String> host = hostOf(url);
		if (host.isEmpty() || !belongsTo(host.get(), primary())) {
			return url;
		}
		String original = host.get();
		String moved = original.substring(0, original.length() - primary().length()) + site;
		int at = url.toLowerCase(Locale.ROOT).indexOf(original);
		return url.substring(0, at) + moved + url.substring(at + original.length());
	}

	private Optional<String> domainOfHost(String host) {
		return domains.stream().filter(d -> belongsTo(host, d)).findFirst();
	}

	private static boolean belongsTo(String host, String domain) {
		return host.equals(domain) || host.endsWith("." + domain);
	}

	private static Optional<String> hostOf(String url) {
		if (!StringUtils.hasText(url)) {
			return Optional.empty();
		}
		try {
			String host = URI.create(url.trim()).getHost();
			return host == null ? Optional.empty() : Optional.of(host.toLowerCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
