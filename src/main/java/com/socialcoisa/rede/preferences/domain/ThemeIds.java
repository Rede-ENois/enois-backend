package com.socialcoisa.rede.preferences.domain;

import java.util.Set;

/**
 * Identificadores de tema que o backend aceita — o espelho da coleção do front.
 *
 * O backend não conhece a estética de cada tema (isso é todo CSS no front); aqui
 * vive só a lista do que é válido, para não persistir lixo na conta. Um tema novo
 * no front entra nessa lista no mesmo commit.
 *
 * A lista está agrupada pela família da coleção, na mesma ordem da galeria:
 * clássicos de sistema, coleções da casa, pontos de save de jogo, máquinas antigas,
 * papel e tinta e, por fim, a nova geração (Java Swing, internet 2000, arcade e
 * computação clássica).
 */
public final class ThemeIds {

	public static final Set<String> ALL = Set.of(
		// Sistema retrô
		"win95", "win98", "win2000", "luna", "aero-glass", "frutiger-aero", "metro",
		"terminal", "vaporwave", "synthwave", "y2k", "neumorfismo",
		// Coleções da casa
		"blahaj", "intersection", "venus", "aquiles", "twomoons",
		// Pontos de save
		"silver-jonny", "caravana-do-vento", "bruma-silenciosa", "laboratorio-alaranjado",
		"vale-esmeralda", "cidade-suspensa", "ilhota-de-tinta", "vagao-noturno", "jardim-de-luz",
		"brasas-cinzentas", "reino-do-bloco", "cume-celeste", "peste-dourada", "kart-tropical",
		"estrela-de-papel",
		// Máquinas antigas
		"prompt-do-dos", "bancada-amiga", "estacao-preta", "listras-amarelas", "toca-de-palmeira",
		"tijolo-confiavel", "tela-verde", "papel-e-maquina", "conde-noturno", "norte-frio",
		"caixa-rustica", "sol-e-sombra", "contraste-puro",
		// Papel e tinta
		"oceano", "floresta", "deserto-ao-entardecer", "aurora", "lavanda", "musgo", "pessego",
		"grafite", "champanhe", "oliva", "tinta", "papel-kraft", "gelo", "vinho", "madrugada",
		// Nova geração — Java Swing
		"swing-metal", "swing-ocean", "swing-nimbus", "swing-motif", "swing-windows", "swing-dark",
		// Nova geração — internet dos anos 2000
		"web2000", "web2000-forum", "web2000-cybercafe", "web2000-glass", "web2000-portal",
		"web2000-pixel",
		// Nova geração — arcade e jogos
		"arcade-8bit", "arcade-16bit", "arcade-dungeon", "arcade-space", "arcade-wasteland",
		"arcade-strategy", "arcade-rpg", "arcade-terminal",
		// Nova geração — computação clássica
		"unix-classic", "terminal-green", "amber-crt", "workbench", "industrial-desktop");

	private ThemeIds() {
	}
}
