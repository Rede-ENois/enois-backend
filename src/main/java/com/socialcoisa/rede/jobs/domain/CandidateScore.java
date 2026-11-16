package com.socialcoisa.rede.jobs.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Score de triagem de uma candidatura — <b>explicável</b>: todo ponto vem de um sinal que a pessoa
 * consegue ler ("3 de 5 requisitos — Java, Spring"), nunca de um número opaco.
 *
 * <p>É calculado sobre o retrato da candidatura (o perfil no momento em que ela foi feita), não
 * sobre o perfil de hoje: o recrutador decide com o que o candidato tinha quando se candidatou.
 *
 * <p>Como no {@link JobCompatibility}, só entra no total o que os dois lados informam, e o peso é
 * redistribuído entre os sinais que existem. Sem requisito e sem localidade na vaga não há
 * comparação a fazer: {@code available = false} e a tela diz isso — nunca "0% compatível".
 *
 * <p>Identidade verificada entra como sinal do retrato, com o motivo escrito ao lado. O score
 * ordena a triagem; ele não reprova ninguém sozinho, e a recusa continua sendo ato humano.
 */
public record CandidateScore(int total, boolean available, List<Component> components) {

	/** Peso do sinal de requisitos da vaga no total. */
	static final int SKILLS_WEIGHT = 75;

	/** Peso do sinal de identidade verificada. */
	static final int IDENTITY_WEIGHT = 25;

	public record Component(String code, String label, int points, int maxPoints) {
	}

	public static CandidateScore of(List<String> candidateSkills, boolean identityVerified, Job job) {
		List<Component> components = new ArrayList<>();
		Double skills = skillsRatio(candidateSkills, job);
		if (skills != null) {
			components.add(new Component("SKILLS", skillsLabel(candidateSkills, job),
				round(skills * SKILLS_WEIGHT), SKILLS_WEIGHT));
		}
		components.add(new Component("IDENTITY",
			identityVerified ? "Identidade verificada" : "Identidade não verificada",
			identityVerified ? IDENTITY_WEIGHT : 0, IDENTITY_WEIGHT));

		int weight = (skills != null ? SKILLS_WEIGHT : 0) + IDENTITY_WEIGHT;
		double total = (skills != null ? skills * SKILLS_WEIGHT : 0) + (identityVerified ? IDENTITY_WEIGHT : 0);
		boolean available = skills != null;
		int score = available ? (int) Math.round(total / weight * 100) : 0;
		return new CandidateScore(score, available, List.copyOf(components));
	}

	/** Fração dos requisitos da vaga que o retrato cobre; nulo quando não há o que comparar. */
	private static Double skillsRatio(List<String> candidateSkills, Job job) {
		List<String> candidate = clean(candidateSkills);
		List<String> required = clean(job.skills());
		if (candidate.isEmpty() || required.isEmpty()) {
			return null;
		}
		long matched = required.stream().filter(skill -> coveredBy(skill, candidate)).count();
		return (double) matched / required.size();
	}

	private static String skillsLabel(List<String> candidateSkills, Job job) {
		List<String> candidate = clean(candidateSkills);
		List<String> required = clean(job.skills());
		List<String> hit = required.stream().filter(skill -> coveredBy(skill, candidate)).toList();
		if (hit.isEmpty()) {
			return "Nenhum dos " + required.size() + " requisitos da vaga: " + String.join(", ", required);
		}
		return hit.size() + " de " + required.size() + " requisitos da vaga: " + String.join(", ", hit);
	}

	/** O retrato cobre este requisito? Igualdade normalizada ou um contendo o outro. */
	private static boolean coveredBy(String required, List<String> candidate) {
		String key = normalize(required);
		return !key.isBlank() && candidate.stream().anyMatch(mine -> {
			String skill = normalize(mine);
			return !skill.isBlank() && (skill.equals(key) || skill.contains(key) || key.contains(skill));
		});
	}

	private static List<String> clean(List<String> values) {
		return values == null ? List.of()
			: values.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT)
			.trim();
	}

	private static int round(double value) {
		return (int) Math.round(value);
	}
}
