package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Endereço de mídia do ambiente: o QA devolvia endereços de produção porque a URL gravada no asset
 * é a do ambiente em que ele nasceu (e o QA nasceu de uma cópia). A troca é de host, e só quando o
 * host de mídia configurado é o deste ambiente.
 */
class MediaPublicHostTests {

	private static final String QA = "https://enois-qa.b7cloud.tech";
	private static final String QA_API = "https://enois-api-qa.b7cloud.tech";
	private static final String QA_CDN = "https://cdn-qa.b7cloud.tech";

	private static MediaPublicHost qa(String mediaBase) {
		return new MediaPublicHost(mediaBase, "", QA_API, QA, QA, "enois.ia.br,isweee.com");
	}

	@Test
	void aProductionMediaUrlBecomesThisEnvironmentsHost() {
		MediaPublicHost media = qa(QA_CDN);

		assertThat(media.localize("https://api.enois.ia.br/api/storage/uploads/media_1/foto.jpg"))
			.isEqualTo(QA_CDN + "/api/storage/uploads/media_1/foto.jpg");
		assertThat(media.localize("https://api.enois.ia.br/api/storage/uploads/b.png"))
			.isEqualTo(QA_CDN + "/api/storage/uploads/b.png");
		// O espelho não entra na troca: o navegador já resolve o espelho por conta própria.
		assertThat(media.localize("https://isweee.com/uploads/a.png")).isEqualTo("https://isweee.com/uploads/a.png");
	}

	@Test
	void productionItselfNeverRewrites() {
		MediaPublicHost media = new MediaPublicHost("https://api.enois.ia.br/api/storage", "", "https://api.enois.ia.br",
			"https://enois.ia.br", "https://enois.ia.br", "enois.ia.br");

		assertThat(media.localize("https://api.enois.ia.br/api/storage/uploads/a.jpg"))
			.isEqualTo("https://api.enois.ia.br/api/storage/uploads/a.jpg");
		assertThat(media.localize("https://isweee.com/uploads/a.jpg")).isEqualTo("https://isweee.com/uploads/a.jpg");
	}

	@Test
	void withoutAConfiguredMediaHostNothingChanges() {
		MediaPublicHost media = qa("");

		assertThat(media.localize("https://api.enois.ia.br/uploads/a.jpg"))
			.isEqualTo("https://api.enois.ia.br/uploads/a.jpg");
	}

	@Test
	void anotherPeopleHostAndRelativeValuesAreLeftAlone() {
		MediaPublicHost media = qa(QA_CDN);

		assertThat(media.localize("https://enois.ia.br.evil.example/uploads/a.jpg"))
			.isEqualTo("https://enois.ia.br.evil.example/uploads/a.jpg");
		assertThat(media.localize("https://fotos.exemplo.com/a.jpg")).isEqualTo("https://fotos.exemplo.com/a.jpg");
		assertThat(media.localize("/uploads/a.jpg")).isEqualTo("/uploads/a.jpg");
		assertThat(media.localize("")).isEmpty();
		assertThat(media.localize(null)).isNull();
	}

	@Test
	void theHostBelongsToTheEnvironmentOnlyByTheSameNameOrASubdomain() {
		assertThat(MediaPublicHost.belongsToEnvironment("enois-qa.b7cloud.tech", QA_API, QA, "")).isTrue();
		assertThat(MediaPublicHost.belongsToEnvironment("cdn-qa.b7cloud.tech", QA_API, QA, QA_CDN)).isTrue();
		assertThat(MediaPublicHost.belongsToEnvironment("api.enois.ia.br", QA_API, QA, "")).isFalse();
		assertThat(MediaPublicHost.belongsToEnvironment("enois-qa.b7cloud.tech.evil.example", QA_API, QA, ""))
			.isFalse();
	}
}