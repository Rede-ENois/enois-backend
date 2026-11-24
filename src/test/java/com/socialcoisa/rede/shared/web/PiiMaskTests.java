package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critico")
class PiiMaskTests {

	@Test
	void removeEnderecoInteiroDoPainel() {
		assertThat(PiiMask.email("fabitmap@gmail.com")).isEqualTo("***");
		assertThat(PiiMask.email("a@b.com")).isEqualTo("***");
		assertThat(PiiMask.email(" sem-arroba ")).isEqualTo("***");
		assertThat(PiiMask.email(null)).isNull();
	}

	@Test
	void enderecoColadoEmAssuntoOuErroTambemSai() {
		assertThat(PiiMask.text("falha para mel@exemplo.com no envio"))
			.isEqualTo("falha para *** no envio");
		assertThat(PiiMask.text(null)).isNull();
	}
}
