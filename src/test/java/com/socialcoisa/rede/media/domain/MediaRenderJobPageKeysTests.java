package com.socialcoisa.rede.media.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer;

/**
 * O limite de {@code page_keys} contra o máximo que o renderizador produz.
 *
 * <p>O renderizador para em {@link MediaPageRenderer#MAX_PAGES} páginas e o serviço grava a chave
 * de cada uma numa única coluna, separada por vírgula. Com {@code varchar(1000)} a 17ª página
 * estourava na gravação, o pedido ficava {@code RENDERED} sem chave — nunca retentado e sem página
 * para moderar —, e a peça ficava bloqueada para sempre. O teste atual do serviço renderiza duas
 * páginas, então não via a conta.</p>
 */
@Tag("critico")
class MediaRenderJobPageKeysTests {

	/** A coluna declarada na V390 (`page_keys`) e no mapeamento da entidade. */
	private static final int COLUNA = 2000;

	@Test
	void vintePaginasCabemNaColunaDePageKeys() {
		// A chave real: `paginas/media_<32 hex>/pagina-<n>.png`, com o lote do serviço.
		String lote = "media_" + UUID.randomUUID().toString().replace("-", "");
		List<String> chaves = new ArrayList<>();
		for (int pagina = 1; pagina <= MediaPageRenderer.MAX_PAGES; pagina++) {
			chaves.add("paginas/" + lote + "/pagina-" + pagina + ".png");
		}

		MediaRenderJob job = new MediaRenderJob("mid_1", "usr_1", MediaRenderJobStatus.PENDING, 0, 0, null, null,
			Instant.parse("2026-11-27T00:00:00Z"), Instant.parse("2026-11-27T00:00:00Z"))
			.withPages(chaves, Instant.parse("2026-11-27T01:00:00Z"));

		String gravado = job.pageKeys();
		assertThat(job.pages()).isEqualTo(MediaPageRenderer.MAX_PAGES);
		assertThat(gravado.length())
			.as("20 chaves de 60 + 19 vírgulas têm de caber em page_keys")
			.isLessThanOrEqualTo(COLUNA);
		// E o que volta do banco tem de reconstruir todas as páginas, na ordem.
		assertThat(job.pageKeyList()).containsExactlyElementsOf(chaves);
	}

	@Test
	void aColunaDeclaradaCobreOMaiorValorQueOCodigoProduz() {
		// Pior caso com o formato atual: 60 caracteres por chave no MAX_PAGES.
		int piorCaso = MediaPageRenderer.MAX_PAGES * 60 + (MediaPageRenderer.MAX_PAGES - 1);
		assertThat(COLUNA).isGreaterThanOrEqualTo(piorCaso);
	}
}
