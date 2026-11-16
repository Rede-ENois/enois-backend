package com.socialcoisa.rede.eroleplay.domain;

import java.util.List;

/**
 * O ADAPTER de um sistema de regras: o pedaço que sabe como AQUELE sistema funciona.
 *
 * <p>O Éroleplay não copia regra de sistema comercial. Um adapter de sistema protegido só existe
 * com autorização de quem detém os direitos; sem isso, o que a plataforma registra é o sistema
 * AUTORAL do mestre (família {@link GameSystemFamily#CUSTOM}) e os sistemas originais que o
 * próprio Éroleplay publica.</p>
 *
 * <p>O contrato é pequeno de propósito: identidade, procedência, família e os atributos da ficha.
 * A semântica mais pesada — rolar um teste, interpretar um crítico — entra na medida em que as
 * fases seguintes precisam dela, sem inventar regra que ninguém pediu.</p>
 *
 * <p>A procedência ({@link #author()}, {@link #license()}, {@link #officialUrl()}) é o que permite
 * a plataforma respeitar direitos: só conteúdo original ou autorizado é registrado, com a autoria
 * e a licença à vista.</p>
 */
public interface SystemAdapter {

	/** O identificador estável do sistema (é o valor que a campanha guarda em {@code gameSystem}). */
	String id();

	/** O nome legível do sistema. */
	String label();

	/** A família do motor que interpreta os resultados deste sistema. */
	GameSystemFamily family();

	/** Quem publica o sistema. */
	String author();

	/** A licença sob a qual o sistema é distribuído. */
	String license();

	/** O link oficial do sistema, quando existir; nulo quando não há. */
	String officialUrl();

	/** Se o sistema vem com a plataforma (original do Éroleplay) ou foi acrescentado depois. */
	boolean builtIn();

	/** Os atributos que este sistema define para a ficha. */
	List<SheetAttribute> attributes();

	/**
	 * Os valores que este sistema CALCULA a partir da ficha. Vazio por padrão: um sistema só
	 * declara derivado quando a conta é regra de verdade — derivado digitado à mão seria a ficha
	 * mentindo sobre o próprio sistema.
	 */
	default List<DerivedAttribute> derivedAttributes() {
		return List.of();
	}
}
