package com.socialcoisa.rede.doacoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.doacoes.domain.PixStatement;

/**
 * A leitura do extrato colado. O que entra é entrada de Pix; o que é saída, boleto, TED ou linha
 * sem valor não entra — errar para o lado de não reconhecer é o jeito seguro, porque a conferência
 * do lado de quem recebe ainda existe.
 */
@Tag("critico")
class PixStatementTests {

	@Test
	void reconheceEntradaComValorEId() {
		String extrato = """
			13/11/2026 Pix recebido
			R$ 25,00
			ID: E20261113202611131200abcdefghijk
			""";

		List<PixStatement.Entry> entradas = PixStatement.received(extrato);

		assertThat(entradas).hasSize(1);
		assertThat(entradas.get(0).amountCents()).isEqualTo(2500);
		assertThat(entradas.get(0).detail()).isEqualTo("E20261113202611131200abcdefghijk");
	}

	@Test
	void ignoraLinhaDeSaida() {
		String extrato = """
			13/11/2026 Pix enviado
			-R$ 25,00
			ID: E20261113202611131200abcdefghijk
			""";

		assertThat(PixStatement.received(extrato)).isEmpty();
	}

	@Test
	void ignoraBoletoETed() {
		String extrato = """
			13/11/2026 Pagamento de boleto
			R$ 120,00
			13/11/2026 TED recebida
			R$ 50,00
			""";

		assertThat(PixStatement.received(extrato)).isEmpty();
	}

	@Test
	void valorSemSinalSoEntraComMarcacaoDeRecebimento() {
		assertThat(PixStatement.received("13/11/2026 R$ 25,00")).isEmpty();
		assertThat(PixStatement.received("13/11/2026 Pix recebido R$ 25,00")).hasSize(1);
	}

	@Test
	void valorComSinalMaisEntraSozinho() {
		assertThat(PixStatement.received("13/11/2026 +R$ 25,00")).hasSize(1);
	}

	@Test
	void separaMilharComPonto() {
		List<PixStatement.Entry> entradas = PixStatement.received("13/11/2026 +R$ 1.234,56");

		assertThat(entradas).hasSize(1);
		assertThat(entradas.get(0).amountCents()).isEqualTo(123456);
	}

	@Test
	void leMaisDeUmaEntrada() {
		String extrato = """
			13/11/2026 +R$ 10,00
			14/11/2026 +R$ 20,00
			""";

		assertThat(PixStatement.received(extrato)).hasSize(2);
	}
}
