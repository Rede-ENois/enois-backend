package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A assinatura é o que impede um intermediário de inventar permissão para o nó. O que os testes
 * fixam, porque cada um é um jeito de errar:
 *
 * <ul>
 * <li>assinar e conferir com a mesma linha canônica funciona;</li>
 * <li>trocar um campo depois de assinar quebra a verificação — é exatamente o ataque;</li>
 * <li>listas com a mesma ordem de conteúdo, mas escritas em ordem diferente na memória, produzem a
 * MESMA linha canônica — a assinatura não pode depender da ordem do banco;</li>
 * <li>assinatura corrompida é recusa limpa, não exceção;</li>
 * <li>chave malformada vira "sem chave", não erro em toda leitura.</li>
 * </ul>
 */
class NetworkConfigSignerTests {

	private final KeyPair pair = generate();

	private static KeyPair generate() {
		try {
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private String sign(String canonical) {
		try {
			var signer = java.security.Signature.getInstance("Ed25519");
			signer.initSign(pair.getPrivate());
			signer.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(signer.sign());
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private NetworkConfigSigner.SignedFields fields(List<String> profiles) {
		return new NetworkConfigSigner.SignedFields(2, 7, "nd_1", profiles, 4, 8192, 51200, 100,
			List.of("MEDIA_TRANSCODE"), "2026-11-12T12:00:00Z", 4);
	}

	@Test
	void assinaEConfereODocumentoCanonico() {
		var fields = fields(List.of("NODE_MEDIA"));
		String signature = sign(fields.canonical());
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), fields.canonical(), signature)).isTrue();
	}

	@Test
	void campoTrocadoDepoisDeAssinarQuebraAVerificacao() {
		var fields = fields(List.of("NODE_MEDIA"));
		String signature = sign(fields.canonical());
		// O ataque: mais perfis do que o Control Plane aprovou.
		var forged = new NetworkConfigSigner.SignedFields(2, 7, "nd_1", List.of("NODE_MEDIA", "NODE_WORKER"), 4, 8192,
			51200, 100, List.of("MEDIA_TRANSCODE"), "2026-11-12T12:00:00Z", 4);
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), forged.canonical(), signature)).isFalse();
		// E a variante mais sorrateira: mesma permissão, número de versão maior — o nó aceitaria uma
		// config que nunca foi emitida só porque "parece mais nova".
		var bumped = new NetworkConfigSigner.SignedFields(2, 8, "nd_1", List.of("NODE_MEDIA"), 4, 8192, 51200, 100,
			List.of("MEDIA_TRANSCODE"), "2026-11-12T12:00:00Z", 4);
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), bumped.canonical(), signature)).isFalse();
	}

	@Test
	void mesmaPermissaoEmOrdemDiferreProduzAMesmaLinhaCanonica() {
		var first = new NetworkConfigSigner.SignedFields(2, 7, "nd_1", List.of("NODE_MEDIA", "NODE_WORKER"), 4, 8192,
			51200, 100, List.of("ASYNC_JOB", "MEDIA_TRANSCODE"), "2026-11-12T12:00:00Z", 4);
		var second = new NetworkConfigSigner.SignedFields(2, 7, "nd_1", List.of("NODE_WORKER", "NODE_MEDIA"), 4, 8192,
			51200, 100, List.of("MEDIA_TRANSCODE", "ASYNC_JOB"), "2026-11-12T12:00:00Z", 4);
		assertThat(first.canonical()).isEqualTo(second.canonical());
		String signature = sign(first.canonical());
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), second.canonical(), signature)).isTrue();
	}

	@Test
	void assinaturaCorrompidaERecusaLimpa() {
		var fields = fields(List.of("NODE_MEDIA"));
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), fields.canonical(), "nao-e-base64!!!")).isFalse();
		assertThat(NetworkConfigSigner.verify(pair.getPublic(), fields.canonical(),
				Base64.getEncoder().encodeToString(new byte[64]))).isFalse();
	}

	@Test
	void chaveMalformadaViraSemChave() {
		assertThat(NetworkConfigSigner.privateKey("lixo")).isEmpty();
		assertThat(NetworkConfigSigner.publicKey("")).isEmpty();
		assertThat(NetworkConfigSigner.privateKey(null)).isEmpty();
		assertThat(NetworkConfigSigner.publicKey(Base64.getEncoder()
			.encodeToString(pair.getPublic().getEncoded()))).isPresent();
	}
}
