package com.socialcoisa.rede.jobs.domain;

import java.util.Locale;

/**
 * Traduz o vocabulário livre das fontes para os códigos que os filtros usam.
 *
 * É tradução, não dedução: "Remoto" e "Remote" viram REMOTE porque dizem a mesma coisa; um texto
 * que não corresponde a nenhum código conhecido fica nulo, e a vaga simplesmente não casa com
 * aquele filtro. Nunca inferir senioridade ou modelo de trabalho a partir do título.
 */
public final class JobTaxonomy {

	private JobTaxonomy() {
	}

	/** REMOTE, HYBRID, ONSITE — ou nulo quando a fonte não diz (ou diz algo que não reconhecemos). */
	public static String workModel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.contains("hybrid") || value.contains("híbrido") || value.contains("hibrido")
				|| value.contains("misto")) {
			return "HYBRID";
		}
		if (value.contains("remote") || value.contains("remoto") || value.contains("remota")
				|| value.contains("home office") || value.contains("teletrabalho")) {
			return "REMOTE";
		}
		if (value.contains("on-site") || value.contains("onsite") || value.contains("on site")
				|| value.contains("presencial")) {
			return "ONSITE";
		}
		return null;
	}

	/** JUNIOR, MID, SENIOR, LEAD — ou nulo. */
	public static String level(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.contains("intern") || value.contains("estágio") || value.contains("estagio")
				|| value.contains("trainee") || value.contains("aprendiz")) {
			return "JUNIOR";
		}
		if (value.contains("junior") || value.contains("júnior") || value.contains("entry")) {
			return "JUNIOR";
		}
		if (value.contains("senior") || value.contains("sênior") || value.contains("sr.")) {
			return "SENIOR";
		}
		if (value.contains("lead") || value.contains("staff") || value.contains("principal")
				|| value.contains("especialista") || value.contains("coordinator") || value.contains("manager")) {
			return "LEAD";
		}
		if (value.contains("mid") || value.contains("pleno") || value.contains("intermediate")) {
			return "MID";
		}
		return null;
	}

	/** HOUR, DAY, MONTH, YEAR — o período a que a faixa salarial se refere. */
	public static String salaryPeriod(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.contains("hour") || value.contains("hora")) {
			return "HOUR";
		}
		if (value.contains("day") || value.contains("dia")) {
			return "DAY";
		}
		if (value.contains("year") || value.contains("ano") || value.contains("annual")) {
			return "YEAR";
		}
		if (value.contains("month") || value.contains("mês") || value.contains("mes")
				|| value.contains("mensal")) {
			return "MONTH";
		}
		return null;
	}

	/**
	 * Vínculo em código estável: CLT, PJ, INTERNSHIP, FREELANCE, FULL_TIME, PART_TIME, CONTRACT,
	 * TEMPORARY. Texto desconhecido volta em maiúsculas como veio — melhor mostrar o termo da fonte
	 * do que forçá-lo numa categoria errada.
	 */
	public static String employmentType(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.contains("clt")) {
			return "CLT";
		}
		if (value.contains("pj") || value.contains("pessoa jurídica") || value.contains("pessoa juridica")) {
			return "PJ";
		}
		if (value.contains("estágio") || value.contains("estagio") || value.contains("internship")
				|| value.contains("intern")) {
			return "INTERNSHIP";
		}
		if (value.contains("freelance") || value.contains("freela")) {
			return "FREELANCE";
		}
		if (value.contains("part") || value.contains("meio período") || value.contains("meio periodo")) {
			return "PART_TIME";
		}
		if (value.contains("contract") || value.contains("contrato")) {
			return "CONTRACT";
		}
		if (value.contains("temp")) {
			return "TEMPORARY";
		}
		if (value.contains("full") || value.contains("integral") || value.contains("tempo integral")) {
			return "FULL_TIME";
		}
		return raw.trim().toUpperCase(Locale.ROOT);
	}
}
