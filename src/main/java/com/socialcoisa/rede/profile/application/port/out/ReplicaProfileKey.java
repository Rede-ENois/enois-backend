package com.socialcoisa.rede.profile.application.port.out;

import java.time.Instant;

/**
 * Um item da enumeração da réplica de leitura: a chave de ordenação e o <b>@</b> do perfil.
 *
 * <p>Devolve o @, e não o id, porque é o @ que a rota de conteúdo aceita ({@code
 * /network/content/PROFILE/{username}}). Devolver o id daria à réplica uma chave que ela não
 * consegue trocar por corpo sem uma segunda consulta — e o catálogo existe justamente para dizer "o
 * que ler", não "o que procurar depois".</p>
 *
 * <p>Nome, contagens e personalização não vêm por aqui de propósito: viriam de um segundo caminho
 * de serialização para a mesma projeção, e a réplica leva a mesma que a borda leva.</p>
 */
public record ReplicaProfileKey(String userId, String username, Instant createdAt) {
}