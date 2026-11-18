package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Política de cache por content-type (brief §13).
 *
 * <p>A regra que este teste protege é a de segurança: <b>o padrão é privado</b>. Um tipo que ninguém
 * classificou não vira cacheável por omissão — cache público de documento de uma pessoa é vazamento
 * para todo proxy no caminho.</p>
 */
class CachePolicyTests {

	@Test
	void imagem_e_video_e_audio_sao_publicos_e_imutaveis() {
		assertThat(CachePolicy.forContentType("image/png")).isEqualTo(CachePolicy.PUBLIC_MEDIA);
		assertThat(CachePolicy.forContentType("video/mp4")).isEqualTo(CachePolicy.PUBLIC_MEDIA);
		assertThat(CachePolicy.forContentType("audio/mpeg")).isEqualTo(CachePolicy.PUBLIC_MEDIA);
		// Caixa alta não muda a classificação: o content-type é case-insensitive.
		assertThat(CachePolicy.forContentType("IMAGE/JPEG")).isEqualTo(CachePolicy.PUBLIC_MEDIA);
		assertThat(CachePolicy.PUBLIC_MEDIA.headerValue()).contains("public").contains("immutable");
	}

	@Test
	void documento_e_tipo_desconhecido_sao_privados() {
		assertThat(CachePolicy.forContentType("application/pdf")).isEqualTo(CachePolicy.PRIVATE);
		assertThat(CachePolicy.forContentType("text/plain")).isEqualTo(CachePolicy.PRIVATE);
		assertThat(CachePolicy.forContentType(null)).isEqualTo(CachePolicy.PRIVATE);
		assertThat(CachePolicy.PRIVATE.headerValue()).contains("no-store");
	}

	/** A entrega para nó nunca é cacheável, mesmo quando o tipo é imagem. */
	@Test
	void entrega_para_no_nunca_e_publica() {
		assertThat(CachePolicy.NODE.headerValue()).contains("no-store").doesNotContain("public");
	}
}
