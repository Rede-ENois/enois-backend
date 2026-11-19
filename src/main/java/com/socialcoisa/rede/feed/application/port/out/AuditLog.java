package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;

/**
 * A leitura de uma linha de auditoria pelo que ela prova: o alvo, o metadado e o instante.
 *
 * <p>Existe para a disponibilidade do nó (§18): o {@code offlineForSeconds} de cada reconexão vive
 * no metadado, e a soma só é honesta lendo a linha inteira em vez de contar ocorrências. A ordem é
 * a da consulta (mais recente primeiro), porque "a última ausência" é a primeira que se lê.</p>
 */
public record AuditLog(String id, String actorId, String action, String targetId, String metadata, Instant createdAt) {
}
