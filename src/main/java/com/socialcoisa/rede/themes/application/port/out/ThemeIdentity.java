package com.socialcoisa.rede.themes.application.port.out;

/**
 * O que a limpeza do marketplace precisa saber de um tema: quem o publicou.
 *
 * <p>Ler o {@code ThemePackage} inteiro traz o {@code package_json} (a paleta e o CSS do tema) para
 * decidir uma comparação de autor. Em um lote de até 500 temas isso é transferir megabytes de JSON
 * que ninguém vai ler.</p>
 */
public record ThemeIdentity(String id, String authorId) {
}