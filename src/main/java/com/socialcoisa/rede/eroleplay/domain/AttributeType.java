package com.socialcoisa.rede.eroleplay.domain;

/**
 * O tipo de um atributo de ficha.
 *
 * <p>O sistema de regras escolhido é que decide COMO a ficha é lida (Fase 3). O Éroleplay só
 * precisa saber o suficiente para desenhar o campo e guardar o valor sem mentir: um número é
 * número, um texto é texto, uma escolha tem opções.</p>
 */
public enum AttributeType {

	/** Valor numérico (um modificador, um nível, um ponto de vida). */
	NUMBER,

	/** Texto curto (um nome, um título). */
	TEXT,

	/** Texto longo (história, anotações do jogador). */
	LONGTEXT,

	/** Sim ou não. */
	BOOLEAN,

	/** Uma opção entre as declaradas. */
	CHOICE
}
