package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import com.socialcoisa.rede.shared.web.DuplicatePostFilter;

/**
 * O filtro do canal tem de rodar antes do filtro de duplicidade.
 *
 * <p>Os dois leem o corpo. O de duplicidade usa o corpo como chave da janela anti-duplo-clique; se
 * ele rodasse primeiro, veria o envelope — que muda a cada envio — e cada clique viraria uma
 * requisição distinta. O teste falha se alguém reordenar os dois.</p>
 */
class CryptoFilterOrderTests {

	@Test
	void canalRodaAntesDaDeduplicacao() {
		int canal = ordem(CryptoChannelFilter.class);
		int duplicidade = ordem(DuplicatePostFilter.class);

		assertThat(canal).isLessThan(duplicidade);
	}

	private static int ordem(Class<?> tipo) {
		Order order = tipo.getAnnotation(Order.class);
		assertThat(order).as("classe sem @Order: " + tipo.getSimpleName()).isNotNull();
		return order.value();
	}
}