package com.socialcoisa.rede.jobs.domain;

import java.util.Locale;

/**
 * Campo personalizado do formulario de candidatura, definido por quem publica a vaga.
 *
 * Cada campo e obrigatorio ou opcional — e essa marca e o que a candidatura valida no envio.
 */
public class JobApplicationField {

	public enum FieldType {
		/** Resposta curta. */
		TEXT,
		/** Resposta longa. */
		TEXTAREA,
		/** Numero (anos de experiencia, pretensao, ...). */
		NUMBER,
		/** Link (portfolio, LinkedIn, GitHub). */
		LINK;

		public static FieldType from(String value) {
			if (value == null || value.isBlank()) {
				return TEXT;
			}
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException exception) {
				throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_FIELD_TYPE",
					"Tipo de campo invalido: " + value + ". Use TEXT, TEXTAREA, NUMBER ou LINK.");
			}
		}
	}

	private final String id;
	private final String jobId;
	private final String label;
	private final FieldType type;
	private final boolean required;
	private final int position;
	/** Campo de origem da condicional; nulo = sempre visível. */
	private final String visibleIfFieldId;
	/** Valor que a resposta do campo de origem precisa casar (normalizada) para revelar este. */
	private final String visibleIfValue;

	/** Restauração sem reverificação: a origem foi validada quando a condicional foi criada. */
	public static JobApplicationField restore(String id, String jobId, String label, FieldType type, boolean required,
			int position, String visibleIfFieldId, String visibleIfValue) {
		return new JobApplicationField(id, jobId, label, type, required, position, visibleIfFieldId, visibleIfValue);
	}

	private JobApplicationField(String id, String jobId, String label, FieldType type, boolean required, int position,
			String visibleIfFieldId, String visibleIfValue) {
		this.id = id;
		this.jobId = jobId;
		this.label = label;
		this.type = type;
		this.required = required;
		this.position = position;
		this.visibleIfFieldId = visibleIfFieldId;
		this.visibleIfValue = visibleIfValue;
	}

	public static JobApplicationField create(String id, String jobId, String label, FieldType type, boolean required,
			int position) {
		return new JobApplicationField(id, jobId, label, type, required, position, null, null);
	}

	/**
	 * Campo com lógica condicional: só existe no formulário quando a resposta do campo de origem
	 * casa com o valor declarado. Origem inexistente ou ciclo (direto ou indireto) é erro do
	 * domínio — formulário que se contradiz não sai para o candidato.
	 */
	public static JobApplicationField conditional(String id, String jobId, String label, FieldType type,
			boolean required, int position, String visibleIfFieldId, String visibleIfValue,
			java.util.function.Function<String, JobApplicationField> fieldById) {
		if (visibleIfFieldId == null || visibleIfFieldId.isBlank()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(422, "CONDITION_REQUIRES_SOURCE",
				"O campo condicional precisa dizer de qual campo depende.");
		}
		String value = visibleIfValue == null ? "" : visibleIfValue.trim();
		if (value.isEmpty()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(422, "CONDITION_REQUIRES_VALUE",
				"O campo condicional precisa dizer qual resposta revela o campo.");
		}
		JobApplicationField source = fieldById.apply(visibleIfFieldId.trim());
		if (source == null || !source.jobId().equals(jobId)) {
			throw new com.socialcoisa.rede.shared.web.ApiException(422, "CONDITION_SOURCE_NOT_FOUND",
				"O campo de origem da condicional não existe nesta vaga.");
		}
		if (id != null && id.equals(source.id())) {
			throw new com.socialcoisa.rede.shared.web.ApiException(422, "CONDITION_CYCLE",
				"O campo não pode depender dele mesmo.");
		}
		// O que fica gravado é o ID da origem resolvida, não o texto que o autor digitou: o
		// rótulo pode ser renomeado depois sem quebrar a condição, e a leitura do formulário
		// precisa de um identificador estável.
		return new JobApplicationField(id, jobId, label, type, required, position, source.id(), value);
	}

	public String id() {
		return id;
	}

	public String jobId() {
		return jobId;
	}

	public String label() {
		return label;
	}

	public FieldType type() {
		return type;
	}

	public boolean required() {
		return required;
	}

	public int position() {
		return position;
	}

	/** Campo de origem da condicional (nulo = sempre visível). */
	public String visibleIfFieldId() {
		return visibleIfFieldId;
	}

	public String visibleIfValue() {
		return visibleIfValue;
	}

	/**
	 * Este campo está visível dadas as respostas já digitadas? Casagem normalizada (caixa e
	 * acento ignorados) — "sim" e "Sim" são a mesma resposta para a lógica.
	 */
	public boolean visibleGiven(java.util.Map<String, String> answersByFieldId) {
		if (visibleIfFieldId == null) {
			return true;
		}
		String answer = answersByFieldId.get(visibleIfFieldId);
		return answer != null && normalize(answer).equals(normalize(visibleIfValue));
	}

	private static String normalize(String value) {
		return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT)
			.trim();
	}
}