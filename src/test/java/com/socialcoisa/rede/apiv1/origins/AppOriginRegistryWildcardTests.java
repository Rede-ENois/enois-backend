package com.socialcoisa.rede.apiv1.origins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Curinga solto continua proibido em qualquer perfil: {@code *} e {@code https://*} deixariam
 * qualquer site chamar a API. Curinga de SUBDOMÍNIO é outra coisa — ele só existe no QA/dev e o
 * registro decide isso por ambiente (ver {@link AppOriginRegistryTests}).
 */
class AppOriginRegistryWildcardTests {

	@Test
	void anOpenWildcardIsRejectedAtBoot() {
		assertThatThrownBy(() -> new AppOriginRegistry("*", "", false))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AppOriginRegistry("https://enois.ia.br", "https://*", true))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AppOriginRegistry("https://enois.ia.br", "*", true))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anExplicitListIsAcceptedInEveryProfile() {
		assertThat(new AppOriginRegistry("https://enois.ia.br,http://localhost:5173", "", false).trusted())
			.contains("https://enois.ia.br", "http://localhost:5173");
	}

	@Test
	void aSubdomainWildcardIsAcceptedOnlyWhereTheProfileAllowsIt() {
		assertThat(new AppOriginRegistry("", "https://*.b7cloud.tech", true).trusted())
			.contains("https://*.b7cloud.tech");
		assertThat(new AppOriginRegistry("", "https://*.b7cloud.tech", false).trusted())
			.doesNotContain("https://*.b7cloud.tech");
	}
}