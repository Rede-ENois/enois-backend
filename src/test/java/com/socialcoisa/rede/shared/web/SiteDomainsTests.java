package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SiteDomainsTests {

	private final SiteDomains sites = new SiteDomains(" enois.ia.br , ISWEEE.com,isweee.com ");

	@Test
	void theFirstDomainIsThePrimaryAndTheListIsNormalized() {
		assertThat(sites.primary()).isEqualTo("enois.ia.br");
		assertThat(sites.all()).containsExactly("enois.ia.br", "isweee.com");
	}

	@Test
	void anOriginMapsToTheSiteItBelongsTo() {
		assertThat(sites.siteOf("https://isweee.com")).isEqualTo("isweee.com");
		assertThat(sites.siteOf("https://api.isweee.com:443")).isEqualTo("isweee.com");
		assertThat(sites.siteOf("https://enois.ia.br")).isEqualTo("enois.ia.br");
	}

	@Test
	void unknownLookalikeOrMissingOriginsMapToThePrimary() {
		assertThat(sites.siteOf("https://isweee.com.example.org")).isEqualTo("enois.ia.br");
		assertThat(sites.siteOf("https://notisweee.com")).isEqualTo("enois.ia.br");
		assertThat(sites.siteOf("https://enois-qa.b7cloud.tech")).isEqualTo("enois.ia.br");
		assertThat(sites.siteOf(null)).isEqualTo("enois.ia.br");
		assertThat(sites.siteOf("")).isEqualTo("enois.ia.br");
		assertThat(sites.siteOf("not a url")).isEqualTo("enois.ia.br");
	}

	@Test
	void localizeMovesPrimaryUrlsToTheMirrorKeepingSubdomainPathAndQuery() {
		assertThat(sites.localize("https://enois.ia.br/auth/callback?provider=GOOGLE", "isweee.com"))
			.isEqualTo("https://isweee.com/auth/callback?provider=GOOGLE");
		assertThat(sites.localize("https://api.enois.ia.br/api/auth/oauth/github/callback", "isweee.com"))
			.isEqualTo("https://api.isweee.com/api/auth/oauth/github/callback");
	}

	@Test
	void localizeLeavesEverythingElseUntouched() {
		String primary = "https://enois.ia.br/auth/callback";
		assertThat(sites.localize(primary, "enois.ia.br")).isEqualTo(primary);
		assertThat(sites.localize(primary, null)).isEqualTo(primary);
		assertThat(sites.localize(primary, "evil.example")).isEqualTo(primary);
		assertThat(sites.localize("https://enois-api-qa.b7cloud.tech/api/x", "isweee.com"))
			.isEqualTo("https://enois-api-qa.b7cloud.tech/api/x");
		assertThat(sites.localize("", "isweee.com")).isEmpty();
		assertThat(sites.localize(null, "isweee.com")).isNull();
	}

	@Test
	void aSingleDomainSetupNeverRewrites() {
		SiteDomains single = new SiteDomains("enois.ia.br");
		assertThat(single.siteOf("https://isweee.com")).isEqualTo("enois.ia.br");
		assertThat(single.localize("https://enois.ia.br/x", "isweee.com")).isEqualTo("https://enois.ia.br/x");
	}

	@Test
	void anEmptyListIsAConfigurationError() {
		assertThatThrownBy(() -> new SiteDomains(" , ")).isInstanceOf(IllegalStateException.class);
	}
}
