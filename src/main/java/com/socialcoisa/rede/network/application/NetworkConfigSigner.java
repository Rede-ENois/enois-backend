package com.socialcoisa.rede.network.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Assinatura Ed25519 da configuração que o nó recebe.
 *
 * <p>Sem assinatura, o runtime teria de confiar no que chegou pela rede: um Control Plane
 * comprometido, um proxy no caminho ou uma resposta forjada passariam permissão nova. Com ela, o
 * nó guarda só a chave pública — que ele recebeu uma vez, no install — e recusa qualquer config
 * que não confira.</p>
 *
 * <p><b>A assinatura cobre o documento canônico, não o JSON.</b> Quem assina e quem confere montam
 * a mesma linha de texto, campo por campo, na ordem do contrato: reordenar chaves no JSON muda
 * bytes e não muda a assinatura. Assinar o corpo serializado amarraria a verificação ao formato de
 * serialização de uma biblioteca.</p>
 *
 * <p><b>Sem chave configurada, a resposta sai sem assinatura e diz {@code signed:false}.</b> Não é
 * fail-open: quem decide se aceita config não assinada é o runtime, e o padrão dele é recusar. Aqui
 * a única obrigação é não mentir — {@code signed:true} sem assinatura seria uma afirmação que o nó
 * não tem como conferir.</p>
 */
final class NetworkConfigSigner {

	private NetworkConfigSigner() {
	}

	/** Rótulo do algoritmo no payload; o nó confere antes de verificar. */
	static final String ALGORITHM = "Ed25519";

	/**
	 * A parte assinada: formato, versão da permissão, identidade, perfis, limites, tarefas permitidas
	 * e o instante em que a permissão passou a valer. O nó sabe se a config envelheceu comparando
	 * {@code issuedAt} com o próprio relógio e sabe se ela mudou comparando {@code configVersion}.
	 *
	 * <p>{@code term} é o termo da autoridade que assinou (fase 6, doc 06 §4): o nó recusa config de
	 * termo menor que o último visto, porque config de termo velho é uma autoridade DEPOSTA tentando
	 * voltar a mandar. Sem o termo na linha assinada, esse número seria editável por quem forja a
	 * resposta — e a regra de recusa seria decorativa.</p>
	 */
	record SignedFields(int formatVersion, int configVersion, String nodeId, List<String> profiles, int cpuCores,
			int memoryMb, int diskMb, int bandwidthMbps, List<String> allowedTasks, String issuedAt, int term) {

		/**
		 * A linha assinada. Listas vão ordenadas e em JSON compacto: a ordem do banco não é
		 * contrato, e duas ordens distintas para o mesmo conjunto de perfis dariam assinaturas
		 * diferentes para a mesma permissão. {@code term} entra no FIM — o formato 1 não o tinha, e
		 * quem reconstrói a linha do formato 2 sempre o acrescenta por último.
		 */
		String canonical() {
			return String.join("|", String.valueOf(formatVersion), String.valueOf(configVersion), nodeId,
				jsonList(profiles), String.valueOf(cpuCores), String.valueOf(memoryMb), String.valueOf(diskMb),
				String.valueOf(bandwidthMbps), jsonList(allowedTasks), issuedAt, String.valueOf(term));
		}

		private static String jsonList(List<String> values) {
			return values.stream()
				.sorted()
				.map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		}
	}

	/** Resultado da verificação — o runtime usa o mesmo par canônico/assinatura. */
	static boolean verify(PublicKey pin, String canonical, String signatureBase64) {
		try {
			Signature verifier = Signature.getInstance(ALGORITHM);
			verifier.initVerify(pin);
			verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(Base64.getDecoder().decode(signatureBase64));
		}
		catch (GeneralSecurityException | IllegalArgumentException invalid) {
			return false;
		}
	}

	/** Chave privada PKCS#8 em base64 — é o formato que as ferramentas de CLI emitem. */
	static Optional<PrivateKey> privateKey(String base64) {
		return parse(base64).flatMap(encoded -> {
			try {
				return Optional.of(KeyFactory.getInstance(ALGORITHM)
					.generatePrivate(new PKCS8EncodedKeySpec(encoded)));
			}
			catch (GeneralSecurityException | IllegalArgumentException unusable) {
				return Optional.empty();
			}
		});
	}

	/** Chave pública X.509 em base64; é o que o instalador grava no nó. */
	static Optional<PublicKey> publicKey(String base64) {
		return parse(base64).flatMap(encoded -> {
			try {
				return Optional.of(KeyFactory.getInstance(ALGORITHM)
					.generatePublic(new X509EncodedKeySpec(encoded)));
			}
			catch (GeneralSecurityException | IllegalArgumentException unusable) {
				return Optional.empty();
			}
		});
	}

	private static Optional<byte[]> parse(String base64) {
		if (base64 == null || base64.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Base64.getDecoder().decode(base64.trim()));
		}
		catch (IllegalArgumentException notBase64) {
			// Chave malformada é configuração quebrada; vira "sem chave", não erro em cada leitura.
			return Optional.empty();
		}
	}
}
