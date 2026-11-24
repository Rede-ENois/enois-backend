package com.socialcoisa.rede.apiv1.origins;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A lista de origens do app é uma só para o portão e para o CORS, e o curinga só existe onde é
 * necessário (QA e dev).
 */

@Tag("critico")
class AppOriginRegistryTests {

	private static final String QA = "https://enois-qa.b7cloud.tech";
	private static final String STRANGER = "https://teste-validacao.b7cloud.tech";

	private static AppOriginRegistry qa() {
		return new AppOriginRegistry(QA, "https://*.b7cloud.tech,https://*.b7cloud.com.br," + QA, true);
	}

	private static AppOriginRegistry production() {
		return new AppOriginRegistry("https://enois.ia.br,https://isweee.com",
			"https://enois.ia.br,https://api.enois.ia.br,https://*.b7cloud.tech", false);
	}

	@Test
	void qaAcceptsTheSubdomainFamilyInGateAndCors() {
		AppOriginRegistry registry = qa();

		assertThat(registry.isTrusted(STRANGER)).isTrue();
		assertThat(registry.corsPatterns()).contains("https://*.b7cloud.tech", QA);
		// ...mas credencial (cookie) continua só na origem explícita.
		assertThat(registry.isCredentialed(STRANGER)).isFalse();
		assertThat(registry.isCredentialed(QA)).isTrue();
	}

	@Test
	void productionDropsWildcardsFromBothLists() {
		AppOriginRegistry registry = production();

		assertThat(registry.isTrusted("https://teste-validacao.b7cloud.tech")).isFalse();
		assertThat(registry.corsPatterns()).noneMatch(pattern -> pattern.contains("*"));
		assertThat(registry.wildcardsAllowed()).isFalse();
		assertThat(registry.isTrusted("https://enois.ia.br")).isTrue();
		assertThat(registry.isTrusted("https://api.enois.ia.br")).isTrue();
	}

	@Test
	void theCorsListIsBuiltOnceAndStaysTheSame() {
		// A lista que o CORS consome é derivada no boot, não a cada requisição: uma instância nova por
		// chamada invalidava o cache do Spring a cada preflight.
		AppOriginRegistry registry = qa();

		assertThat(registry.corsPatterns()).isSameAs(registry.corsPatterns());
		assertThat(registry.corsPatterns()).containsExactlyElementsOf(registry.corsPatterns());
	}

	@Test
	void productionKeepsTheKnownOriginWhenOnlyWildcardsAreConfigured() {
		AppOriginRegistry registry = new AppOriginRegistry("", "https://*.b7cloud.tech", false);

		assertThat(registry.trusted()).containsExactly(AppOriginRegistry.DEFAULT_ORIGIN);
		assertThat(registry.corsPatterns()).containsExactly(AppOriginRegistry.DEFAULT_ORIGIN);
	}

	@Test
	void aQualifiedWildcardStillNeedsARealSubdomain() {
		AppOriginRegistry registry = qa();

		assertThat(registry.isTrusted("https://.b7cloud.tech")).isFalse();
		assertThat(registry.isTrusted("https://b7cloud.tech.evil.example")).isFalse();
		assertThat(registry.isTrusted("https://enois-qa.b7cloud.tech.evil.example")).isFalse();
	}

	@Test
	void withoutConfigurationTheKnownAppOriginIsUsed() {
		AppOriginRegistry registry = new AppOriginRegistry("", "", false);

		assertThat(registry.trusted()).containsExactly(AppOriginRegistry.DEFAULT_ORIGIN);
		assertThat(registry.corsPatterns()).containsExactly(AppOriginRegistry.DEFAULT_ORIGIN);
	}

	@Test
	void theMatchFormIgnoresCaseAndTrailingSlash() {
		assertThat(AppOriginRegistry.matchForm(" HTTPS://Enois.ia.br/ ")).isEqualTo("https://enois.ia.br");
	}
}