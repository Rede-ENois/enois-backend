package com.socialcoisa.rede.doacoes.domain;

import java.nio.charset.StandardCharsets;

/**
 * O BR Code (Pix copia e cola) da doação, montado no servidor.
 *
 * <p>A chave é a mesma do CNPJ, sem valor fixo: quem paga escolhe quanto. O que muda por pessoa é o
 * <b>txid</b> (campo EMV 62-05), que o app do pagador retransmite intacto ao pagar e é o único
 * campo do QR estático que volta ao recebedor. Ele é alfabético e numérico de propósito: são os
 * únicos caracteres que o arranjo admite nesse campo.</p>
 *
 * <p>Decisão registrada em {@code docs/DOACOES.md}: o txid identifica a doação, não a pessoa —
 * qualquer pessoa com o código na mão consegue gerar outro pagamento igual, então o selo nunca
 * nasce do E2E sozinho.</p>
 */
public final class BrCode {

	/** Teto do txid no QR estático (EMV 62-05). */
	public static final int TXID_MAX = 25;

	public record Pix(String keyDigits, String name, String city) {
	}

	private final Pix pix;

	public BrCode(Pix pix) {
		if (pix == null || !pix.keyDigits().matches("\\d{11,14}")) {
			throw new IllegalArgumentException("chave Pix precisa ser CPF (11) ou CNPJ (14 dígitos)");
		}
		this.pix = pix;
	}

	/** O copia e cola do doador, com o txid que a conta recebeu. */
	public String copyAndPaste(String txid) {
		if (txid == null || txid.length() > TXID_MAX || !txid.matches("[A-Za-z0-9]+")) {
			throw new IllegalArgumentException("txid fora do padrão do QR estático");
		}
		String conta = tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("01", pix.keyDigits()));
		String base = tlv("00", "01")
			+ tlv("01", "11")
			+ conta
			+ tlv("52", "0000")
			+ tlv("53", "986")
			+ tlv("58", "BR")
			+ tlv("59", limpar(pix.name(), 25))
			+ tlv("60", limpar(pix.city(), 15))
			+ tlv("62", tlv("05", txid));
		// O CRC cobre o payload inteiro, inclusive o próprio campo 63/04.
		String comCrc = base + "6304";
		return comCrc + crc16(comCrc);
	}

	private static String tlv(String id, String value) {
		String size = Integer.toString(value.length());
		if (size.length() > 2) {
			throw new IllegalArgumentException("campo EMV " + id + " maior que 99 caracteres");
		}
		return id + (size.length() == 1 ? "0" + size : size) + value;
	}

	private static String limpar(String valor, int max) {
		String semAcento = java.text.Normalizer.normalize(valor, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "");
		String limpo = semAcento.replaceAll("[^A-Za-z0-9 ]", "").toUpperCase(java.util.Locale.ROOT).trim();
		return limpo.length() > max ? limpo.substring(0, max).trim() : limpo;
	}

	/** CRC16-CCITT (polinômio 0x1021, init 0xFFFF) em quatro hex maiúsculos. */
	public static String crc16(String payload) {
		int crc = 0xFFFF;
		for (int i = 0; i < payload.length(); i++) {
			crc ^= payload.charAt(i) << 8;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
				crc &= 0xFFFF;
			}
		}
		return String.format("%04X", crc);
	}
}
