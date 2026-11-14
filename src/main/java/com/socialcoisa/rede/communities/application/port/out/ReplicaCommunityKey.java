package com.socialcoisa.rede.communities.application.port.out;

import java.time.Instant;

/**
 * Um item da enumeração da réplica de leitura: a chave de ordenação e o id da comunidade.
 *
 * <p>O criador vem junto porque a elegibilidade da vitrine depende dele (comunidade de conta
 * suspensa é escondida) e resolver isso item a item seria uma consulta por comunidade. Quem filtra
 * é o serviço, que conhece o gate; aqui só os fatos.</p>
 */
public record ReplicaCommunityKey(String communityId, Instant createdAt, String createdBy) {
}
