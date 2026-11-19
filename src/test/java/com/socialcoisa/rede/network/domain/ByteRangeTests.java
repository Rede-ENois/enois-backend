package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * {@code Range} do brief §13, medido contra a RFC 9110.
 *
 * <p>O caso que importa de verdade é o player de vídeo do front: ele pede {@code bytes=N-} para
 * retomar, e recebe 206 ou 416. Servir o corpo inteiro em silêncio faria o player achar que recebeu
 * o trecho e tocar do começo — o bug de vídeo "que volta", que é pior do que um erro.</p>
 */
class ByteRangeTests {

	@Test
	void faixa_com_inicio_e_fim() {
		ByteRange range = ByteRange.parse("bytes=0-99", 1000).orElseThrow();

		assertThat(range.start()).isZero();
		assertThat(range.end()).isEqualTo(99);
		assertThat(range.length()).isEqualTo(100);
		assertThat(range.contentRange()).isEqualTo("bytes 0-99/1000");
	}

	/** {@code bytes=N-} é o que o player manda para retomar: vai do N até o fim. */
	@Test
	void aberta_no_fim_vai_ate_o_ultimo_byte() {
		ByteRange range = ByteRange.parse("bytes=900-", 1000).orElseThrow();

		assertThat(range.start()).isEqualTo(900);
		assertThat(range.end()).isEqualTo(999);
		assertThat(range.length()).isEqualTo(100);
	}

	/** {@code bytes=-n}: os últimos n bytes, usado para ler o átomo final de um MP4. */
	@Test
	void sufixo_pega_os_ultimos_bytes() {
		ByteRange range = ByteRange.parse("bytes=-100", 1000).orElseThrow();

		assertThat(range.start()).isEqualTo(900);
		assertThat(range.end()).isEqualTo(999);
	}

	/** Sufixo maior que o objeto: o objeto inteiro, não uma faixa inventada. */
	@Test
	void sufixo_maior_que_o_objeto_devolve_tudo() {
		ByteRange range = ByteRange.parse("bytes=-5000", 1000).orElseThrow();

		assertThat(range.start()).isZero();
		assertThat(range.end()).isEqualTo(999);
	}

	/**
	 * Fim além do objeto é <b>truncado</b>, não recusado: a RFC manda servir até o fim. Recusar aqui
	 * quebraria o cliente que pede {@code bytes=0-99999} num arquivo de 100 bytes.
	 */
	@Test
	void fim_alem_do_objeto_e_truncado() {
		ByteRange range = ByteRange.parse("bytes=0-99999", 100).orElseThrow();

		assertThat(range.end()).isEqualTo(99);
		assertThat(range.length()).isEqualTo(100);
	}

	/**
	 * Início além do objeto é 416, e não "serve tudo": o cliente pediu algo que não existe, e a RFC
	 * tem um status para isso. Servir 200 aqui esconde o erro do cliente para sempre.
	 */
	@Test
	void inicio_alem_do_objeto_e_insatisfativel() {
		assertThatThrownBy(() -> ByteRange.parse("bytes=1000-", 1000)).isInstanceOf(ByteRange.Unsatisifiable.class);
	}

	/** Cabeçalho ausente, em outra unidade ou malformado: corpo inteiro, que é sempre correto. */
	@Test
	void cabecalho_que_nao_se_aplica_vira_corpo_inteiro() {
		assertThat(ByteRange.parse(null, 1000)).isEmpty();
		assertThat(ByteRange.parse("items=0-10", 1000)).isEmpty();
		assertThat(ByteRange.parse("bytes=abc-def", 1000)).isEmpty();
		assertThat(ByteRange.parse("bytes=50-10", 1000)).isEmpty();
	}

	/** Vários intervalos: recusa em vez de montar multipart à mão e servir errado. */
	@Test
	void varios_intervalos_caem_no_corpo_inteiro() {
		assertThat(ByteRange.parse("bytes=0-1,5-6", 1000)).isEqualTo(Optional.empty());
	}

	/** Objeto vazio não tem byte nenhum para fatiar: nenhuma faixa é válida. */
	@Test
	void objeto_vazio_nao_tem_faixa() {
		assertThat(ByteRange.parse("bytes=0-0", 0)).isEmpty();
	}
}
