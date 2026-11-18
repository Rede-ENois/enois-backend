package com.socialcoisa.rede.network.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A credencial de cliente do gateway S3 (brief §11, V356): o par Access Key / Secret Key que o SDK
 * assina com SigV4.
 *
 * <p><b>É separada da chave de nó, e não uma variação dela.</b> A chave de nó autoriza <i>operar a
 * rede</i> (puxar tarefas, gravar réplica de objeto designado); esta autoriza <i>usar buckets</i>.
 * Rotacionar uma não pode derrubar a outra, e o §10 proíbe compartilhar credencial administrativa com
 * participante — um nó não recebe credencial S3.</p>
 *
 * <p>Escopo: {@code allowedBucket} (nulo = todos os buckets do dono) e {@code allowedOperations} (os
 * verbos S3, separados por espaço). Uma credencial só de leitura é {@code "GET"}; ninguém confere
 * permissão olhando o dono do bucket — a credencial é que diz o que pode.</p>
 */
public record S3Credential(String id, String accessKey, String ownerUserId, String allowedBucket,
		String allowedOperations, Instant revokedAt, Instant createdAt) {

	/** A operação está no escopo desta credencial? */
	public boolean allows(String operation) {
		if (operation == null) {
			return false;
		}
		Set<String> verbos = Arrays.stream(allowedOperations == null ? new String[0] : allowedOperations.split("\\s+"))
			.filter(v -> !v.isBlank())
			.map(v -> v.toUpperCase(java.util.Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
		return verbos.contains(operation.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * O bucket está no escopo? Nulo em {@code allowedBucket} significa "todos os buckets do dono".
	 *
	 * <p>Não existe o literal {@code "*"}: seria ambíguo com um bucket chamado {@code *} (nome válido
	 * de bucket), e "todos" tem de ser dito uma vez só.</p>
	 */
	public boolean allowsBucket(String bucket) {
		return allowedBucket == null || allowedBucket.equals(bucket);
	}

	/** Revogada não autentica, qualquer que seja a operação. */
	public boolean usable() {
		return revokedAt == null;
	}
}