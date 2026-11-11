package com.socialcoisa.rede.profile.application.port.out;

import java.time.Instant;

/**
 * O que o cálculo da credibilidade precisa saber da conta, e NADA mais: quando ela nasceu
 * (componente de consistência) e se a identidade é verificada (gate da nota).
 *
 * <p>Ler o perfil inteiro para isso custa caro — o {@code UserProfile} arrasta as coleções de
 * habilidades, objetivos e links externos e os longtext de bio/capa/aparência. O job do ranking
 * pergunta isto para TODAS as contas de uma vez, então a leitura vem desta projeção.</p>
 */
public record ReputationSeed(String id, Instant createdAt, boolean identityVerified) {
}
