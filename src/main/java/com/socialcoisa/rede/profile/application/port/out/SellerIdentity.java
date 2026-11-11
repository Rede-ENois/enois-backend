package com.socialcoisa.rede.profile.application.port.out;

/**
 * O que as vitrines de serviços mostram de quem presta: nome, foto e a personalização do nome.
 *
 * <p>Ler o perfil inteiro para isso custa caro — o {@code UserProfile} traz as coleções de
 * habilidades, objetivos e links externos, e cada {@code findById} vira quatro consultas. As
 * vitrines perguntam isto para dezenas de prestadores de uma vez, então a leitura vem desta
 * projeção, sem as coleções.</p>
 */
public record SellerIdentity(String id, String name, String avatar, String nameTheme, String nameFont,
		String nameColor, String nameplate) {
}
