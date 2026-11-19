package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.springframework.test.web.servlet.MvcResult;

/**
 * Lê o corpo de uma resposta escrita em {@code StreamingResponseBody}.
 *
 * <p>O {@code perform} do MockMvc volta quando o handler devolve a resposta, não quando a thread
 * de streaming terminou de escrever: ler o buffer logo depois pode devolver vazio, e na suíte
 * inteira (máquina mais lenta) isso virava falha intermitente. Espera o buffer encher até o
 * limite antes de comparar. Antes cada classe tinha a sua cópia deste laço.</p>
 */
public final class StreamedBody {

	private StreamedBody() {
	}

	/** Os bytes do corpo, esperando até 5 s para o streaming terminar. */
	public static byte[] read(MvcResult resultado) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		byte[] corpo = resultado.getResponse().getContentAsByteArray();
		while (corpo.length == 0 && System.nanoTime() < limite) {
			Thread.sleep(10);
			corpo = resultado.getResponse().getContentAsByteArray();
		}
		return corpo;
	}

	/** O corpo é exatamente {@code esperado} (espera o streaming terminar, até 5 s). */
	public static void assertIs(MvcResult resultado, byte[] esperado) throws InterruptedException {
		assertThat(read(resultado)).isEqualTo(esperado);
	}

	/** O corpo não está vazio (espera o streaming terminar, até 5 s). */
	public static void assertNotEmpty(MvcResult resultado) throws InterruptedException {
		assertThat(read(resultado)).isNotEmpty();
	}
}
