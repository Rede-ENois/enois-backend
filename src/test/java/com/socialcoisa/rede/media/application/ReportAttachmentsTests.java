package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.security.Hashes;

/**
 * O endereço da cópia de denúncia. A cópia é conteúdo não revisado: não pode sair num endereço
 * público derivado do id do asset, e a assinatura do endereço curto precisa ser de uso restrito —
 * outro id, outro escopo e outro prazo não abrem o arquivo.
 */
class ReportAttachmentsTests {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
	private static final String SECRET = "segredo-de-teste-com-mais-de-32-caracteres";
	private static final long VALID_UNTIL = 1794744300L;

	private static ReportAttachments of(String secret) {
		return of(secret, "https://api.enois.ia.br");
	}

	private static ReportAttachments of(String secret, String backendUrl) {
		return new ReportAttachments(backendUrl, secret, 300,
			new MediaPublicHost("https://cdn-qa.example", "", "https://api.qa.example", "https://qa.example",
				"https://qa.example", "enois.ia.br"), NOW);
	}

	private final ReportAttachments attachments = of(SECRET, "https://api.enois.ia.br/");

	private static String signatureOf(String mediaId, String scope, long expires) {
		return Hashes.sha256Hex(SECRET + "\n" + mediaId + "\n" + scope + "\n" + expires);
	}

	@Test
	void theModeratorUrlCarriesScopeAndAShortExpiry() {
		assertThat(attachments.moderatorUrl("media_1"))
			.startsWith("https://api.enois.ia.br/media/attachments/media_1?scope=moderator&expires=" + VALID_UNTIL);
		assertThat(attachments.ownerUrl("media_1")).contains("?scope=owner&");
	}

	@Test
	void aSignatureIssuedForOneScopeDoesNotOpenAnother() {
		String moderator = signatureOf("media_1", "moderator", VALID_UNTIL);

		assertThat(attachments.verify("media_1", "moderator", String.valueOf(VALID_UNTIL), moderator)).isPresent();
		assertThat(attachments.verify("media_1", "owner", String.valueOf(VALID_UNTIL), moderator)).isEmpty();
		assertThat(attachments.verify("media_2", "moderator", String.valueOf(VALID_UNTIL), moderator)).isEmpty();
	}

	@Test
	void expiredOrUnknownScopeSignaturesAreRefused() {
		assertThat(attachments.verify("media_1", "moderator", "1794743000",
			signatureOf("media_1", "moderator", 1794743000L))).isEmpty();
		assertThat(attachments.verify("media_1", "public", String.valueOf(VALID_UNTIL),
			signatureOf("media_1", "public", VALID_UNTIL))).isEmpty();
		assertThat(attachments.verify("media_1", "moderator", "nao-e-numero", "x")).isEmpty();
		assertThat(attachments.verify("media_1", "moderator", "", "")).isEmpty();
		assertThat(attachments.verify("", "moderator", String.valueOf(VALID_UNTIL), "x")).isEmpty();
	}

	@Test
	void aShortOrMissingSecretIssuesNoUrlAtAll() {
		ReportAttachments noSecret = of("curto");

		assertThat(noSecret.hasSecret()).isFalse();
		assertThat(noSecret.moderatorUrl("media_1")).isNull();
		assertThat(noSecret.ownerUrl("media_1")).isNull();
		assertThat(noSecret.verify("media_1", "moderator", String.valueOf(VALID_UNTIL), "qualquer")).isEmpty();
	}

	/**
	 * A chave privada é a do MESMO objeto — o caminho que o asset declarava quando a cópia foi
	 * enviada —, só com o prefixo privado na frente.
	 */
	@Test
	void thePrivateKeyIsTheDeclaredObjectPathUnderThePrivatePrefix() {
		assertThat(attachments.keyFor("media_1", "uploads/media_1/file.jpg"))
			.isEqualTo("reports/capture/uploads/media_1/file.jpg");
		assertThat(attachments.keyFor("media_1", "/media_1/file.jpg"))
			.isEqualTo("reports/capture/uploads/media_1/file.jpg");
		// Idempotente: chamar com a chave já privada devolve a mesma chave (o endereço do asset é,
		// ao mesmo tempo, onde o objeto está e o que a subida confere).
		String privada = attachments.keyFor("media_1", "uploads/media_1/file.jpg");
		assertThat(attachments.keyFor("media_1", privada)).isEqualTo(privada);
	}
}