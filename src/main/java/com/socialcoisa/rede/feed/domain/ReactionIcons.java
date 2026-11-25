package com.socialcoisa.rede.feed.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.List;

/**
 * Os ícones do pacote É Nóis, um por reação (a mesma ordem do {@link ReactionType}).
 *
 * O front desenha a reação pelo código (`:enois_salute:` no texto e no seletor) e a API continua
 * gravando o tipo, que é o que o banco e os clientes antigos entendem — este mapa é a ponte entre
 * os dois. Ele vive no domínio porque a regra "toda reação tem ícone" é do domínio: o teste ao lado
 * falha se alguém acrescentar um tipo sem o ícone correspondente.
 *
 * Não há duas reações com o mesmo ícone: cada expressão é uma reação própria, mesmo quando o
 * desenho lembra outro emoji.
 */
public final class ReactionIcons {

	/** Nome do código no pacote, sem o prefixo `enois_`: o front usa `enois_<código>`. */
	private static final Map<ReactionType, String> CODIGO = new EnumMap<>(ReactionType.class);

	static {
		CODIGO.put(ReactionType.LIKE, "like");
		CODIGO.put(ReactionType.LOVE, "love");
		CODIGO.put(ReactionType.LAUGH, "joy");
		CODIGO.put(ReactionType.ROFL, "sparkle");
		CODIGO.put(ReactionType.SKULL, "wow");
		CODIGO.put(ReactionType.WOW, "angry");
		CODIGO.put(ReactionType.SAD, "cool");
		CODIGO.put(ReactionType.SOB, "cry");
		CODIGO.put(ReactionType.CELEBRATE, "sob");
		CODIGO.put(ReactionType.SUPPORT, "sleep");
		CODIGO.put(ReactionType.INSIGHTFUL, "think");
		CODIGO.put(ReactionType.ANGRY, "nerd");
		CODIGO.put(ReactionType.DISGUST, "party");
		CODIGO.put(ReactionType.SKEPTICAL, "confetti");
		CODIGO.put(ReactionType.EYES, "coffee");
		CODIGO.put(ReactionType.NOTE, "selfie");
		CODIGO.put(ReactionType.FIRE, "thermometer");
		CODIGO.put(ReactionType.CLAP, "pirate");
		CODIGO.put(ReactionType.CLOWN, "hero");
		CODIGO.put(ReactionType.NAILS, "zany");
		CODIGO.put(ReactionType.USEFUL, "scream");
		CODIGO.put(ReactionType.RELATABLE, "grimace");
		CODIGO.put(ReactionType.HYPE, "melting");
		CODIGO.put(ReactionType.THANKS, "pleading");
		CODIGO.put(ReactionType.VIBE, "eyes");
		CODIGO.put(ReactionType.SALUTE, "mindblown");
		CODIGO.put(ReactionType.COOL, "vomit");
		CODIGO.put(ReactionType.SLEEP, "nauseated");
		CODIGO.put(ReactionType.THINK, "skull");
		CODIGO.put(ReactionType.NERD, "eyeroll");
		CODIGO.put(ReactionType.PARTY, "rocket");
		CODIGO.put(ReactionType.COFFEE, "wizard");
		CODIGO.put(ReactionType.SELFIE, "chef");
		CODIGO.put(ReactionType.THERMOMETER, "surf");
		CODIGO.put(ReactionType.PIRATE, "magic");
		CODIGO.put(ReactionType.HERO, "rebel");
		CODIGO.put(ReactionType.ZANY, "wink");
		CODIGO.put(ReactionType.SCREAM, "grin");
		CODIGO.put(ReactionType.GRIMACE, "rage");
		CODIGO.put(ReactionType.MELTING, "mad");
		CODIGO.put(ReactionType.CRYING, "money");
		CODIGO.put(ReactionType.MINDBLOWN, "fear");
		CODIGO.put(ReactionType.VOMIT, "wave");
		CODIGO.put(ReactionType.FUMING, "peace");
		CODIGO.put(ReactionType.EYEROLL, "ok");
		CODIGO.put(ReactionType.ROBOT, "rock");
		CODIGO.put(ReactionType.WIZARD, "dislike");
		CODIGO.put(ReactionType.CHEF, "clap");
		CODIGO.put(ReactionType.SURF, "raised");
		CODIGO.put(ReactionType.MAGIC, "pray");
		CODIGO.put(ReactionType.REBEL, "gasp");
		CODIGO.put(ReactionType.WINK, "hug");
		CODIGO.put(ReactionType.ALIEN, "mask");
		CODIGO.put(ReactionType.RAGE, "cowboy");
		CODIGO.put(ReactionType.MONEY, "smirk");
		CODIGO.put(ReactionType.FEAR, "ghost");
		CODIGO.put(ReactionType.WAVE, "poop");
		CODIGO.put(ReactionType.PEACE, "robot");
		CODIGO.put(ReactionType.OK, "alien");
		CODIGO.put(ReactionType.ROCK, "monocle");
		CODIGO.put(ReactionType.DISLIKE, "vulcan");
		CODIGO.put(ReactionType.RAISED, "crossed");
		CODIGO.put(ReactionType.GASP, "point_right");
		CODIGO.put(ReactionType.POOP, "fistbump");
		CODIGO.put(ReactionType.TONGUE, "muscle");
		CODIGO.put(ReactionType.KISS, "starstruck");
		CODIGO.put(ReactionType.DROOL, "tongue");
		CODIGO.put(ReactionType.HEART_PURPLE, "heart_purple");
		CODIGO.put(ReactionType.STARSTRUCK, "kiss");
		CODIGO.put(ReactionType.VULCAN, "drool");
		CODIGO.put(ReactionType.CROSSED, "insightful");
		CODIGO.put(ReactionType.POINT_RIGHT, "note");
		CODIGO.put(ReactionType.FISTBUMP, "fire");
		CODIGO.put(ReactionType.MASK, "clown");
		CODIGO.put(ReactionType.COWBOY, "nails");
		CODIGO.put(ReactionType.SMIRK, "useful");
		CODIGO.put(ReactionType.GHOST, "salute");
	}

	private ReactionIcons() {
	}

	/** Códigos do pacote, na ordem do enum — o teste ao lado confere a bijeção com as 77 reações. */
	public static List<String> codigos() {
		return ReactionType.values().length == CODIGO.size()
			? CODIGO.values().stream().toList()
			: falha("Reação sem ícone no pacote É Nóis");
	}

	private static List<String> falha(String mensagem) {
		throw new IllegalStateException(mensagem);
	}

	/** Código do ícone da reação no pacote É Nóis (sem o prefixo `enois_`). */
	public static String codigo(ReactionType type) {
		String codigo = CODIGO.get(type);
		if (codigo == null) {
			// Reação nova sem ícone: falha cedo, no boot/teste, em vez de chegar na tela.
			throw new IllegalStateException("Reação sem ícone no pacote É Nóis: " + type);
		}
		return codigo;
	}
}