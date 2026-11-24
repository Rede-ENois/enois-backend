package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;
import java.util.List;

/**
 * O que o banco de talentos do recrutamento lê de uma pessoa.
 *
 * <p>Vive no perfil porque é o módulo dono dos dados do candidato — quem abre a lista é o módulo
 * de vagas, mas a resposta sobre "quem esta pessoa é" continua aqui. A busca é feita DO BANCO e
 * sempre restrita a um conjunto de ids: o módulo de vagas chega com os consentimentos ativos e
 * pede, entre eles, quem casa. É essa restrição que garante que ninguém fora do banco apareça,
 * mesmo que o critério de busca mude.
 */
public interface TalentCandidateReader {

	/**
	 * Ids do conjunto que casam com a busca, na ordem em que casaram melhor.
	 *
	 * @param candidateIds só estes podem voltar — é o consentimento
	 * @param query sintaxe booleana de {@code shared.search.BooleanQuery}; vazio casa com todos
	 */
	List<String> searchAmong(Collection<String> candidateIds, String query, int limit);

	/**
	 * Ids do conjunto que casam com os filtros de competência, experiência e formação.
	 *
	 * <p>Filtro vazio é "sem filtro": quem não quiser filtrar por um campo não o envia. A restrição
	 * ao conjunto é a mesma da busca — o filtro decide entre os ids liberados, nunca amplia o
	 * conjunto.</p>
	 */
	List<String> filterAmong(Collection<String> candidateIds, TalentCandidateFilter filter);

	/** O extrato de cada conta do conjunto, em uma consulta. */
	List<TalentCandidate> byIds(Collection<String> candidateIds);

	/**
	 * O extrato do banco de talentos: o suficiente para a linha da lista sem repetir a ficha
	 * completa do perfil.
	 */
	record TalentCandidate(String userId, String username, String name, String headline, String avatar,
			String location, String resumeUrl, String resumeName, int experienceCount) {

		public String displayName() {
			return name == null || name.isBlank() ? username : name;
		}
	}

	/** Os filtros do painel de candidatos: competência, experiência mínima e formação. */
	record TalentCandidateFilter(String skill, Integer minExperiences, String education) {

		public boolean isEmpty() {
			return (skill == null || skill.isBlank()) && (minExperiences == null || minExperiences <= 0)
					&& (education == null || education.isBlank());
		}
	}
}
