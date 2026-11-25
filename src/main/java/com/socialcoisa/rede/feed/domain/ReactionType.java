package com.socialcoisa.rede.feed.domain;

/**
 * Reações do post, do comentário, do grupo, do desabafo, da colagem, do confessionário e da
 * mensagem.
 *
 * São as 77 expressões do pacote oficial É Nóis, na ordem do seletor e do {@code ENOIS_CUSTOM_EMOJIS}
 * do front: a reação i é o ícone i do pacote ({@link ReactionIcons}), sem repetição. Nenhuma reação
 * existe fora dele — o que é de BTTV/FrankerFaceZ é figurinha de texto, não reação.
 *
 * Os 26 primeiros são as reações que já existiam; os demais entraram depois. Reação nova entra no
 * FIM: o tipo é gravado no banco pelo nome (EnumType.STRING), então mexer na ordem só muda a ordem
 * das pílulas — nunca o que já foi gravado.
 *
 * Os nomes que ficam colados na lista são os antigos (os que o app já manda); os outros dizem o que
 * a expressão é no ícone, e não o emoji de onde ela veio: {@code ROFL} é a estrela brilhante,
 * {@code WOW} é o bravo fumegando, {@code LAUGH} é a risada. Nenhum é "algo mais": cada tipo é UM
 * ícone, e o usuário escolhe pelo desenho.
 */
public enum ReactionType {
	// As 26 originais: humor, apoio e os seis contextuais. Não mudam de nome nem de ordem.
	LIKE,
	LOVE,
	LAUGH,
	ROFL,
	SKULL,
	WOW,
	SAD,
	SOB,
	CELEBRATE,
	SUPPORT,
	INSIGHTFUL,
	ANGRY,
	DISGUST,
	SKEPTICAL,
	EYES,
	NOTE,
	FIRE,
	CLAP,
	CLOWN,
	NAILS,
	/** Dica/tutorial/INSIGHT prático: serviu de ferramenta de verdade. */
	USEFUL,
	/** Experiência ou desabafo: "me identifiquei". */
	RELATABLE,
	/** Lançamento, projeto, vaga: empolgação com o que vem. */
	HYPE,
	/** Ajuda ou resposta que resolveu: gratidão. */
	THANKS,
	/** Estética, foto, colagem: a vibe do post. */
	VIBE,
	/** Gesto corajoso ou generoso: respeito. */
	SALUTE,

	// O resto das expressões É Nóis, agora também reações.
	/** Estiloso, "mitou". */
	COOL,
	/** Sono, "já vou". */
	SLEEP,
	/** Dúvida genuína: "hmm". */
	THINK,
	/** Algo técnico, nerdice. */
	NERD,
	/** Comemoração; aniversário. */
	PARTY,
	/** Pausa para o café: pausa merecida. */
	COFFEE,
	/** Autorretrato: "eu" com contexto. */
	SELFIE,
	/** Indisposição, corpo cobrando. */
	THERMOMETER,
	/** Post que a pessoa assumiu/aprendeu na marra. */
	PIRATE,
	/** Alguém que resolveu demais: herói. */
	HERO,
	/** Zoeira sem noção, o post doido do grupo. */
	ZANY,
	/** Sustos reais. */
	SCREAM,
	/** Situação constrangedora. */
	GRIMACE,
	/** Situação insustentável: derretendo. */
	MELTING,
	/** Choro de emoção, "por favor". */
	CRYING,
	/** Mente explodida com a informação. */
	MINDBLOWN,
	/** Nojo franco. */
	VOMIT,
	/** Raiva fervendo. */
	FUMING,
	/** Revirando os olhos: paciência esgotada. */
	EYEROLL,
	/** Robô: bot, IA, "processando". */
	ROBOT,
	/** Algo tem cara de magia/truque. */
	WIZARD,
	/** Comida boa, cozinha. */
	CHEF,
	/** Onda boa, feriadão, praia. */
	SURF,
	/** Algo parece truque/cartola. */
	MAGIC,
	/** Rebeldia, "faço porque quero". */
	REBEL,
	/** Piscadinha, "au revoir". */
	WINK,
	/** Alien: fora do planeta, "não entendi nada". */
	ALIEN,
	/** Raiva correndo pelo teclado. */
	RAGE,
	/** Grana, gasto, investimento. */
	MONEY,
	/** Pânico, "e agora?". */
	FEAR,
	/** Oi/tchau no chat. */
	WAVE,
	/** Paz, vitória, "tamo junto". */
	PEACE,
	/** Concordou, "beleza". */
	OK,
	/** Show, "toca o hino". */
	ROCK,
	/** Discordo; não curti. */
	DISLIKE,
	/** Aplausos, parabéns. */
	RAISED,
	/** Risada disfarçada, "ops". */
	GASP,
	/** Cocô roxo: zoeira, alguém errou feio. */
	POOP,
	/** Graça solta, língua pra fora. */
	TONGUE,
	/** Beijo no coração. */
	KISS,
	/** Vontade de algo delicioso. */
	DROOL,
	/** Encantado, olhos de estrela. */
	STARSTRUCK,
	/** Sinais de vida vulcana: "vida longa e próspera". */
	VULCAN,
	/** Dedos cruzados, torcendo. */
	CROSSED,
	/** Apontando o que importa. */
	POINT_RIGHT,
	/** Toca aqui, parceria. */
	FISTBUMP,
	/** Doente: máscara, saúde. */
	MASK,
	/** "Yeehaw": rodeio, campo, cowboy. */
	COWBOY,
	/** Sorriso malandro, "eu sei". */
	SMIRK,
	/** Fantasma: apagar, sumir, "bu". */
	GHOST,
	/**
	 * O coração roxo do pacote: o carinho da fileira de reações, ao lado do joinha. Entrou por
	 * último porque o tipo é gravado pelo nome — a posição aqui só diz onde ele nasceu na lista.
	 */
	HEART_PURPLE
}