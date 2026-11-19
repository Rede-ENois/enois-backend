package com.socialcoisa.rede.network.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A política de ciclo de vida de um bucket (brief §14, V352).
 *
 * <p>O brief pede versionamento <b>opcional por bucket</b>, retenção configurável e expiração
 * automática. Nenhuma das três é obrigatória: o padrão de {@link #padrao(String)} é o
 * comportamento que a rede já tinha — sem versionamento, sem prazo —, e criar a tabela não muda
 * nada de quem não configurou.</p>
 *
 * <p><b>Expiração e retenção são coisas diferentes</b> e por isso são campos diferentes:</p>
 * <ul>
 * <li>{@code expireAfterDays} conta do instante em que o objeto foi <b>gravado</b> e se aplica ao
 * objeto corrente: "24 horas para o arquivo temporário".</li>
 * <li>{@code retainSupersededDays} conta do instante em que a versão foi <b>substituída</b> e se
 * aplica só a ela: "guarde a versão anterior por 30 dias, depois pode coletar".</li>
 * </ul>
 * <p>Confundir as duas faria um bucket com versionamento vencer o arquivo corrente pelo relógio da
 * substituição, ou um bucket sem versionamento nunca coletar lixo.</p>
 */
public record StorageBucketPolicy(String bucket, boolean versioning, Integer retainSupersededDays,
		Integer expireAfterDays, String updatedBy, Instant updatedAt) {

	/** Bucket que ninguém configurou: sem versão, sem prazo. */
	public static StorageBucketPolicy padrao(String bucket) {
		return new StorageBucketPolicy(bucket, false, null, null, null, null);
	}

	/** O instante em que um objeto gravado agora vence ({@code null} = nunca). */
	public Instant expiresAtFor(Instant storedAt) {
		return expireAfterDays == null ? null : storedAt.plus(Duration.ofDays(expireAfterDays));
	}

	/** O instante em que uma versão substituída agora pode ser coletada ({@code null} = nunca). */
	public Instant purgeAfterForSupersededAt(Instant supersededAt) {
		return retainSupersededDays == null ? null : supersededAt.plus(Duration.ofDays(retainSupersededDays));
	}
}