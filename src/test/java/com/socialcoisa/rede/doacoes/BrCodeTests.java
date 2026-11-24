package com.socialcoisa.rede.doacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.doacoes.domain.BrCode;

/**
 * O BR Code da doação: o mesmo formato que o app do banco já aceitou (chave de CNPJ em 14 dígitos,
 * nome sem acento, CRC sobre o payload inteiro), com o txid de cada pessoa no campo 62-05.
 */
@Tag("critico")
class BrCodeTests {

	private final BrCode codigo = new BrCode(new BrCode.Pix("51839682000161", "Apoie o Énóis", "SAO PAULO"));

	@Test
	void montaOCodigoComATxidDaPessoa() {
		String payload = codigo.copyAndPaste("ENOISABCDEFGHJKLMN");

		// O campo 62-05 traz o txid: "05" + tamanho + txid, dentro do campo 62.
		assertThat(payload).contains("05" + "ENOISABCDEFGHJKLMN".length() + "ENOISABCDEFGHJKLMN");
		assertThat(payload).contains("br.gov.bcb.pix");
		assertThat(payload).contains("51839682000161");
		// O nome vai sem acento, como o EMV exige.
		assertThat(payload).contains("APOIE O ENOIS");
	}

	@Test
	void oCrcCobreOPayloadInteiro() {
		String payload = codigo.copyAndPaste("ENOISABCDEFGHJKLMN");

		String semCrc = payload.substring(0, payload.length() - 4);
		assertThat(semCrc).endsWith("6304");
		assertThat(payload.substring(payload.length() - 4)).isEqualTo(BrCode.crc16(semCrc));
	}

	@Test
	void recusaTxidForaDoPadrao() {
		assertThatThrownBy(() -> codigo.copyAndPaste("txid com espaço"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codigo.copyAndPaste("A".repeat(26)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recusaChaveQueNaoEChavePix() {
		assertThatThrownBy(() -> new BrCode(new BrCode.Pix("123", "X", "Y")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
