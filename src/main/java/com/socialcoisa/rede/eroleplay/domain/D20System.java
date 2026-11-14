package com.socialcoisa.rede.eroleplay.domain;

import java.util.List;

/**
 * O sistema D20 ORIGINAL do Éroleplay.
 *
 * <p>Não é cópia de nenhum sistema comercial: o Éroleplay descreve aqui apenas o motor genérico
 * de dado de 20 faces que ele mesmo entrega — seis atributos, um nível e os dois números que
 * qualquer mesa dessa família acompanha (pontos de vida e defesa). O que é conteúdo protegido
 * (nomes de perícias, magias, classes, progressão de um sistema publicado) NÃO mora aqui, e só
 * entra com autorização de quem detém os direitos.</p>
 *
 * <p>Uma campanha que escolhe {@code d20-generico} declara família D20, e o Registry resolve este
 * adapter pelo identificador. Trocar de sistema é trocar o identificador, sem mexer no mundo.</p>
 */
public class D20System implements SystemAdapter {

	public static final String ID = "d20-generico";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String label() {
		return "D20 Éroleplay";
	}

	@Override
	public GameSystemFamily family() {
		return GameSystemFamily.D20;
	}

	@Override
	public String author() {
		return "Éroleplay";
	}

	@Override
	public String license() {
		return "conteúdo original do Éroleplay";
	}

	@Override
	public String officialUrl() {
		return null;
	}

	@Override
	public boolean builtIn() {
		return true;
	}

	/** Os seis atributos e os dois números de combate, como o motor genérico lê uma ficha D20. */
	@Override
	public List<SheetAttribute> attributes() {
		return List.of(
			SheetAttribute.of("forca", "Força", AttributeType.NUMBER),
			SheetAttribute.of("destreza", "Destreza", AttributeType.NUMBER),
			SheetAttribute.of("constituicao", "Constituição", AttributeType.NUMBER),
			SheetAttribute.of("inteligencia", "Inteligência", AttributeType.NUMBER),
			SheetAttribute.of("sabedoria", "Sabedoria", AttributeType.NUMBER),
			SheetAttribute.of("carisma", "Carisma", AttributeType.NUMBER),
			SheetAttribute.of("nivel", "Nível", AttributeType.NUMBER),
			SheetAttribute.of("pontos_de_vida", "Pontos de Vida", AttributeType.NUMBER),
			SheetAttribute.of("defesa", "Defesa", AttributeType.NUMBER),
			SheetAttribute.choice("origem", "Origem", List.of("Acadêmico", "Artista", "Combatente", "Devoto",
				"Engenhoso", "Forasteiro", "Nômade", "Operário", "Popular", "Sócio")),
			HISTORIA);
	}

	/**
	 * Os modificadores dos seis atributos, como a família D20 os lê: {@code (valor - 10) / 2}.
	 *
	 * <p>É conta, não conteúdo: a fórmula é do motor genérico que o Éroleplay publica. Derivado não
	 * é gravado — sai do valor validado na leitura, então não há como a ficha carregar um
	 * modificador que não corresponde ao atributo.</p>
	 */
	@Override
	public List<DerivedAttribute> derivedAttributes() {
		return List.of(
			DerivedAttribute.modifier("forca", "Força"),
			DerivedAttribute.modifier("destreza", "Destreza"),
			DerivedAttribute.modifier("constituicao", "Constituição"),
			DerivedAttribute.modifier("inteligencia", "Inteligência"),
			DerivedAttribute.modifier("sabedoria", "Sabedoria"),
			DerivedAttribute.modifier("carisma", "Carisma"));
	}

	private static final SheetAttribute HISTORIA = new SheetAttribute("historia", "História do Personagem",
		AttributeType.LONGTEXT, false, List.of());
}
