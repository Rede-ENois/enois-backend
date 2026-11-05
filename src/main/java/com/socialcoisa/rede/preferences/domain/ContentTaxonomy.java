package com.socialcoisa.rede.preferences.domain;

import java.util.List;
import java.util.Set;

/**
 * A taxonomia de assuntos e a lista de módulos da personalização.
 *
 * Os assuntos começaram como os das comunidades e foram refinados (pedido da dona, 2026-10-20:
 * "cria mais assuntos... a categorização tá errada" — 37 de 60 comunidades eram TECNOLOGIA e 11
 * OUTROS, grosseiro demais). Os oito valores originais continuam válidos: comunidades e posts
 * antigos não mudam de mão. Os novos entram DEPOIS deles e o autor escolhe o assunto do post
 * no compositor (post avulso nasce categorizado, não só o de comunidade). Os selos ficam de
 * fora de propósito: são sinais editoriais dos leitores, não assuntos. Os módulos são as áreas
 * que o usuário pode desativar na própria experiência; EVENTS também controla o "Acontecendo
 * agora" (dependência do plano).
 */
public final class ContentTaxonomy {

	/** Assuntos válidos para "O que quero ver", "Assuntos que não gosto" e para o post. */
	public static final List<String> TOPICS = List.of(
		// Os oito originais, na ordem de sempre (comunidades antigas dependem deles).
		"TECNOLOGIA", "ENGENHARIA", "DESIGN", "PRODUTO", "LIDERANCA", "CARREIRA", "FREELANCE", "OUTROS",
		// Refinamento (2026-10-20): o que a plataforma realmente abriga.
		"GAMES", "ART", "MUSIC", "EDUCACAO", "CIENCIA", "EMPREENDER", "FINANCAS", "MARKETING",
		"RH", "DADOS",
		// Ampliação (2026-10-20): a rede não é só de tecnologia. Entram os assuntos que as
		// pessoas publicam e querem filtrar fora do mundo dev — humor, saúde, casa, pets,
		// família, política, fé, moda. Comunidades e posts antigos seguem válidos.
		"FUN", "QUALIDADE_VIDA", "SAUDE", "VIAGEM", "COMIDA", "ESPORTES", "CINEMA", "LIVROS",
		"FOTOGRAFIA", "CASA", "PETS", "PARENTALIDADE", "RELACIONAMENTOS", "MODA", "POLITICA",
		"RELIGIAO");
	/** Os oito originais, para não reclassificar nada que já existe. */
	public static final List<String> ORIGINAL_TOPICS = List.of(
		"TECNOLOGIA", "ENGENHARIA", "DESIGN", "PRODUTO", "LIDERANCA", "CARREIRA", "FREELANCE", "OUTROS");

	/** Módulos desativáveis em "O que não quero ver". */
	public static final List<String> MODULES = List.of(
		"COMMUNITIES", "GROUPS", "MESSAGES", "BLOGS", "JOBS", "COMPANIES",
		"MENTORING", "EVENTS", "DAILY_GAMES", "COMMUNITY_GAMES", "COMMUNITY_PROJECTS", "STUDY_PAIRS");

	public static final Set<String> TOPIC_SET = Set.copyOf(TOPICS);
	public static final Set<String> MODULE_SET = Set.copyOf(MODULES);

	private ContentTaxonomy() {
	}
}
