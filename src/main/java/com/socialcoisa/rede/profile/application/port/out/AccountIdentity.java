package com.socialcoisa.rede.profile.application.port.out;

/**
 * O mínimo que a triagem precisa saber de uma conta: existe, e com que nome e @ ela se apresenta.
 *
 * <p>Ler o perfil inteiro para isso custa caro — o {@code UserProfile} traz as coleções de
 * habilidades, objetivos e links externos. As rotinas de limpeza perguntam isto para centenas de
 * contas de uma vez, então a leitura vem desta projeção, sem as coleções.</p>
 */
public record AccountIdentity(String id, String name, String username) {
}