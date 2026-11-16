package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O CPF é a identificação de quem responde por um nó; guardar um número que parece CPF e não é
 * transfere uma decisão administrativa para um dado falso.
 *
 * <p>O que estes testes fixam: os dois verificadores contam, repetição não passa, e a máscara nunca
 * devolve o número inteiro.</p>
 */
class CpfTests {

	@Test
	void aceitaCpfValidoComOuSemMascara() {
		assertThat(Cpf.parse("529.982.247-25")).isPresent();
		assertThat(Cpf.parse("52998224725")).get().extracting(Cpf::digits).isEqualTo("52998224725");
	}

	@Test
	void recusaOPrimeiroVerificadorErrado() {
		// 52998224735: último dígito certo, o anterior trocado. Sem conferir o PRIMEIRO verificador
		// este número passaria — é o caso que só uma validação completa pega.
		assertThat(Cpf.parse("52998224735")).isEmpty();
	}

	@Test
	void recusaOUltimoVerificadorErrado() {
		assertThat(Cpf.parse("52998224726")).isEmpty();
	}

	@Test
	void recusaRepeticaoQuePassaNaAritmetica() {
		// 111.111.111-11 fecha a conta dos verificadores e não é CPF de ninguém.
		assertThat(Cpf.parse("11111111111")).isEmpty();
		assertThat(Cpf.parse("00000000000")).isEmpty();
	}

	@Test
	void recusaTamanhoErradoEVazio() {
		assertThat(Cpf.parse("5299822472")).isEmpty();
		assertThat(Cpf.parse("529982247251")).isEmpty();
		assertThat(Cpf.parse("")).isEmpty();
		assertThat(Cpf.parse(null)).isEmpty();
	}

	@Test
	void aMascaraNaoDevolveONumeroInteiro() {
		Cpf cpf = Cpf.parse("52998224725").orElseThrow();
		assertThat(cpf.masked()).isEqualTo("***.***.***-25").doesNotContain("52998224");
	}
}