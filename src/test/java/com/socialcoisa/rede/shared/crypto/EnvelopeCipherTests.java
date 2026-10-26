package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * O envelope é a base de tudo que vai ser cifrado em repouso: se ele errar, o dado corrompe.
 *
 * <p>Cada teste aqui existe por um modo de falha real: registro movido de linha (AAD), cifra
 * adulterada (tag), rotação de chave (kid), formato quebrado e chave ausente.</p>
 */
class EnvelopeCipherTests {

	private static final String AAD = "users:email:user_123";

	private static String randomKeyBase64() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	private static EnvelopeCipher cipher(String activeKid) {
		return new EnvelopeCipher(new MapKeyProvider(activeKid,
			Map.of("k1", randomKeyBase64(), "k2", randomKeyBase64())));
	}

	@Test
	void idaEVoltaPreservaTextoComAcentoEEmoji() {
		EnvelopeCipher cipher = cipher("k1");
		String claro = "José da Silva — e-mail: jose@exemplo.invalid 🔐";

		String envelope = cipher.encrypt(claro, AAD);

		assertThat(envelope).startsWith("v1:k1:");
		assertThat(cipher.decrypt(envelope, AAD)).isEqualTo(claro);
	}

	@Test
	void duasCifrasDoMesmoTextoDiferemPorCausaDoIv() {
		EnvelopeCipher cipher = cipher("k1");

		String a = cipher.encrypt("mesmo conteudo", AAD);
		String b = cipher.encrypt("mesmo conteudo", AAD);

		assertThat(a).isNotEqualTo(b);
		assertThat(cipher.decrypt(a, AAD)).isEqualTo(cipher.decrypt(b, AAD));
	}

	@Test
	void envelopeDeOutroRegistroNaoAbre() {
		EnvelopeCipher cipher = cipher("k1");
		String envelope = cipher.encrypt("telefone da pessoa", AAD);

		// Mover a cifra para outra linha/coluna é exatamente o ataque que o AAD barra.
		assertThatThrownBy(() -> cipher.decrypt(envelope, "users:phone:user_123"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("INTEGRITY_FAILED"));
	}

	@Test
	void cifraAdulteradaNaoAbre() {
		EnvelopeCipher cipher = cipher("k1");
		String envelope = cipher.encrypt("conteudo original", AAD);
		String[] parts = envelope.split(":", 3);
		byte[] payload = Base64.getDecoder().decode(parts[2]);
		payload[payload.length - 1] ^= 0x01;
		String adulterado = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(payload);

		assertThatThrownBy(() -> cipher.decrypt(adulterado, AAD))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("INTEGRITY_FAILED"));
	}

	@Test
	void chaveDesconhecidaFalhaComCodigoProprio() {
		EnvelopeCipher cipher = cipher("k1");
		String envelope = cipher.encrypt("conteudo", AAD);
		String comOutraChave = "v1:k9:" + envelope.split(":", 3)[2];

		assertThatThrownBy(() -> cipher.decrypt(comOutraChave, AAD))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_UNKNOWN"));
	}

	@Test
	void envelopeMalformadoTemCodigoProprio() {
		EnvelopeCipher cipher = cipher("k1");

		for (String ruim : new String[] { "", "sem-formato", "v2:k1:AAAA", "v1::AAAA", "v1:k1:###" }) {
			assertThatThrownBy(() -> cipher.decrypt(ruim, AAD))
				.as("envelope: '%s'", ruim)
				.isInstanceOfSatisfying(CryptoException.class,
					failure -> assertThat(failure.code()).isEqualTo("MALFORMED_ENVELOPE"));
		}
	}

	@Test
	void aadEmBrancoEProibido() {
		EnvelopeCipher cipher = cipher("k1");

		assertThatThrownBy(() -> cipher.encrypt("conteudo", " "))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("AAD_REQUIRED"));
	}

	@Test
	void rotacaoDeChaveMantemOEnvelopeAntigoLegivel() {
		// k1 cifrou; k2 passa a ser a ativa. O material de k1 continua no chaveiro, senão o
		// registro antigo viraria lixo — que é exatamente o risco que a rotação mal feita cria.
		String materialK1 = randomKeyBase64();
		EnvelopeCipher antigo = new EnvelopeCipher(new MapKeyProvider("k1", Map.of("k1", materialK1)));
		String envelope = antigo.encrypt("dado historico", AAD);
		EnvelopeCipher novo = new EnvelopeCipher(new MapKeyProvider("k2", Map.of(
			"k1", materialK1,
			"k2", randomKeyBase64())));

		assertThat(novo.decrypt(envelope, AAD)).isEqualTo("dado historico");
		assertThat(novo.encrypt("dado novo", AAD)).startsWith("v1:k2:");
	}

	@Test
	void provedorRecusaChaveComTamanhoErrado() {
		String curta = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> new MapKeyProvider("k1", Map.of("k1", curta)))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_INVALID"));
	}

	@Test
	void provedorRecusaChaveAtivaAusente() {
		assertThatThrownBy(() -> new MapKeyProvider("k2", Map.of("k1", randomKeyBase64())))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_INVALID"));
	}

	@Test
	void reconheceEnvelopeDoFormato() {
		EnvelopeCipher cipher = cipher("k1");

		assertThat(EnvelopeCipher.isEnvelope(cipher.encrypt("x", AAD))).isTrue();
		assertThat(EnvelopeCipher.isEnvelope("texto em claro")).isFalse();
		assertThat(EnvelopeCipher.isEnvelope(null)).isFalse();
	}
}
