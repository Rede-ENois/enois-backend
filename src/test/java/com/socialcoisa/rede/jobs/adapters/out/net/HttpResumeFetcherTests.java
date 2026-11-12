package com.socialcoisa.rede.jobs.adapters.out.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

/**
 * O download do currículo é o único ponto em que o Énóis busca um endereço escolhido pela pessoa:
 * o guard de saída tem que ser consultado antes de qualquer conexão, e o nome do anexo tem que
 * chegar legível em quem recebe.
 */
class HttpResumeFetcherTests {

	private final OutboundUrlGuard guard = mock(OutboundUrlGuard.class);

	@Test
	void aRelativeUploadPathIsResolvedAgainstTheImageKitEndpoint() {
		HttpResumeFetcher fetcher = new HttpResumeFetcher(guard, "https://ik.imagekit.io/socialcoisa/");

		assertThat(fetcher.absolute("/uploads/curriculo.pdf"))
			.isEqualTo("https://ik.imagekit.io/socialcoisa/uploads/curriculo.pdf");
	}

	@Test
	void anAbsoluteUrlIsKeptAsIs() {
		HttpResumeFetcher fetcher = new HttpResumeFetcher(guard, "");

		assertThat(fetcher.absolute("https://cdn.test/curriculo.pdf"))
			.isEqualTo("https://cdn.test/curriculo.pdf");
	}

	@Test
	void aBlankUrlIsNotFetched() {
		HttpResumeFetcher fetcher = new HttpResumeFetcher(guard, "");

		assertThat(fetcher.absolute("  ")).isNull();
		assertThat(fetcher.fetch("  ")).isEmpty();
		verify(guard, never()).checked(anyString());
	}

	@Test
	void aDestinationRefusedByTheGuardIsNeverContacted() {
		when(guard.checked(anyString())).thenReturn(Optional.empty());
		HttpResumeFetcher fetcher = new HttpResumeFetcher(guard, "");

		assertThat(fetcher.fetch("http://169.254.169.254/latest/meta-data")).isEmpty();
		verify(guard).checked("http://169.254.169.254/latest/meta-data");
	}

	@Test
	void theAttachmentNameComesFromThePath() {
		assertThat(HttpResumeFetcher.fileName("https://cdn.test/files/curriculo-mel.pdf", "application/pdf"))
			.isEqualTo("curriculo-mel.pdf");
	}

	@Test
	void aPathWithoutExtensionGetsOneFromTheContentType() {
		assertThat(HttpResumeFetcher.fileName("https://cdn.test/files/curriculo", "application/pdf"))
			.isEqualTo("curriculo.pdf");
		assertThat(HttpResumeFetcher.fileName("https://cdn.test/files/curriculo",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
			.isEqualTo("curriculo.docx");
		assertThat(HttpResumeFetcher.fileName("https://cdn.test/files/curriculo", "application/msword"))
			.isEqualTo("curriculo.doc");
	}

	@Test
	void anUnknownContentTypeLeavesTheNameWithoutExtension() {
		assertThat(HttpResumeFetcher.fileName("https://cdn.test/files/curriculo", "text/plain"))
			.isEqualTo("curriculo");
	}

	@Test
	void aUrlWithoutAPathFallsBackToTheDefaultName() {
		assertThat(HttpResumeFetcher.fileName("https://cdn.test", "application/pdf")).isEqualTo("curriculo.pdf");
	}
}
