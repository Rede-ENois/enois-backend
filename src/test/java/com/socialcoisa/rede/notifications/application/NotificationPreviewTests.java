package com.socialcoisa.rede.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A prévia é o trecho do conteúdo de origem que a lista de notificações mostra (issue #392).
 * Estes casos travam o que ela pode conter: texto legível, sem marcação, curto — e nada quando só
 * há mídia.
 */
class NotificationPreviewTests {

	@Test
	void stripsHtmlSoThePreviewIsReadableText() {
		assertThat(NotificationPreview.of("<p>Olha o que eu aprendi hoje sobre <b>Kotlin</b></p>"))
			.isEqualTo("Olha o que eu aprendi hoje sobre Kotlin");
	}

	@Test
	void stripsMarkdownLightMarkers() {
		assertThat(NotificationPreview.of("## Título\n*primeiro* item e [link](https://exemplo.dev)"))
			.isEqualTo("Título primeiro item e link");
	}

	@Test
	void dropsImagesAndKeepsTheLinkText() {
		assertThat(NotificationPreview.of("![foto](https://cdn/foto.png) legenda")).isEqualTo("legenda");
	}

	@Test
	void returnsNullWhenThereIsNoTextOnlyMedia() {
		assertThat(NotificationPreview.of("   ")).isNull();
		assertThat(NotificationPreview.of(null)).isNull();
		assertThat(NotificationPreview.of("![foto](https://cdn/foto.png)")).isNull();
		assertThat(NotificationPreview.of("<video src=\"x.mp4\"></video>")).isNull();
	}

	@Test
	void cutsLongTextWithEllipsis() {
		String preview = NotificationPreview.of("a".repeat(NotificationPreview.MAX_CHARS + 50));

		assertThat(preview).hasSize(NotificationPreview.MAX_CHARS + 1);
		assertThat(preview).endsWith("…");
	}
}
