package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.SiteDomains;

class PageContentCspTests {

	@Test
	void everySiteDomainMayEmbedThePageAndNothingElse() {
		String csp = PageContentController.defaultCsp(new SiteDomains("enois.ia.br,isweee.com"));

		assertThat(csp).endsWith("frame-ancestors 'self' https://enois.ia.br https://*.enois.ia.br "
			+ "https://isweee.com https://*.isweee.com");
		assertThat(csp).contains("script-src 'none'; ");
	}

	@Test
	void aSingleDomainKeepsThePreviousPolicy() {
		assertThat(PageContentController.defaultCsp(new SiteDomains("enois.ia.br")))
			.endsWith("base-uri 'none'; frame-ancestors 'self' https://enois.ia.br https://*.enois.ia.br");
	}
}
