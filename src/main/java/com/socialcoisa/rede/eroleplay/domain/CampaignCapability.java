package com.socialcoisa.rede.eroleplay.domain;

/**
 * Uma ação que alguém pode (ou não) fazer dentro de uma campanha.
 *
 * <p>A lista é a fonte da verdade da autorização: cada endpoint pergunta por uma CAPACIDADE em vez
 * de comparar papéis na mão, então a regra mora num lugar só e mudar a régua não exige caçar
 * {@code if} pelo módulo inteiro.</p>
 *
 * <p>A separação entre "ver" e "administrar" existe porque a campanha tem duas camadas de
 * segredo: o que o grupo todo vê e o que só o mestre vê. Ver a campanha nunca é o mesmo que ver
 * o que só o mestre pode ver.</p>
 */
public enum CampaignCapability {
	/** Ver a campanha por dentro: descrição, ambientação, sistema, participantes e mesas. */
	VIEW_CAMPAIGN,
	/** Editar nome, descrição, ambientação, sistema de regras e visibilidade. */
	MANAGE_CAMPAIGN,
	/** Convidar jogadores, remover jogadores e decidir pedidos de entrada. */
	MANAGE_MEMBERS,
	/** Passar o comando da campanha para outro participante. */
	TRANSFER_MASTERSHIP,
	/** Criar e editar mesas. */
	MANAGE_TABLES,
	/** Publicar no chat da campanha e conversar nos canais em que participa. */
	POST_IN_CAMPAIGN,
	/** Fazer rolagens e pedir rolagens ao mestre. */
	ROLL_DICE
}
