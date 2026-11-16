package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Modelo de processo seletivo: a biblioteca pessoal de fluxos de etapas de quem recruta.
 *
 * <p>Guardar os nomes das etapas em JSON (não em tabela filha) é escolha deliberada: o modelo é
 * um texto de configuração que se aplica por CÓPIA — aplicar num job semeia
 * {@link SelectionStage}s próprias da vaga, e editar o modelo depois não muda vaga configurada.
 * Nada aqui referencia vaga.
 */
public class SelectionProcessTemplate {

	public static final int MAX_NAME = 120;
	/** Mesmo teto do fluxo da vaga: modelo mais largo que isso não cabe numa vaga. */
	public static final int MAX_STAGES = SelectionStage.MAX_STAGES_PER_JOB;
	public static final int MAX_STAGE_NAME = SelectionStage.MAX_NAME;

	private final String id;
	private final String ownerId;
	private String name;
	private List<String> stageNames;
	private final Instant createdAt;
	private Instant updatedAt;

	private SelectionProcessTemplate(String id, String ownerId, String name, List<String> stageNames,
			Instant createdAt, Instant updatedAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.ownerId = Objects.requireNonNull(ownerId, "dono");
		this.createdAt = Objects.requireNonNull(createdAt, "criação");
		this.updatedAt = Objects.requireNonNull(updatedAt, "atualização");
		this.name = checkName(name);
		this.stageNames = checkStages(stageNames);
	}

	public static SelectionProcessTemplate create(String id, String ownerId, String name,
			List<String> stageNames, Instant now) {
		return new SelectionProcessTemplate(id, ownerId, name, stageNames, now, now);
	}

	public static SelectionProcessTemplate restore(String id, String ownerId, String name,
			List<String> stageNames, Instant createdAt, Instant updatedAt) {
		return new SelectionProcessTemplate(id, ownerId, name, stageNames, createdAt, updatedAt);
	}

	private static String checkName(String name) {
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty() || clean.length() > MAX_NAME) {
			throw new ApiException(422, "INVALID_TEMPLATE_NAME",
				"O nome do modelo precisa ter entre 1 e " + MAX_NAME + " caracteres.");
		}
		return clean;
	}

	private static List<String> checkStages(List<String> stageNames) {
		List<String> clean = stageNames == null ? List.of() : stageNames.stream()
			.map(name -> name == null ? "" : name.trim())
			.filter(name -> !name.isEmpty())
			.toList();
		if (clean.isEmpty()) {
			throw new ApiException(422, "TEMPLATE_STAGES_REQUIRED",
				"O modelo precisa de pelo menos uma etapa.");
		}
		if (clean.size() > MAX_STAGES) {
			throw new ApiException(422, "TOO_MANY_STAGES",
				"O modelo pode ter no máximo " + MAX_STAGES + " etapas.");
		}
		for (String stageName : clean) {
			if (stageName.length() > MAX_STAGE_NAME) {
				throw new ApiException(422, "INVALID_STAGE_NAME",
					"Cada etapa pode ter no máximo " + MAX_STAGE_NAME + " caracteres.");
			}
		}
		// Nomes repetidos no modelo virariam etapas que a regra da vaga recusa (nome único por
		// vaga); recusar na origem é mais claro que falhar na hora de aplicar.
		long distinct = clean.stream().map(n -> n.toLowerCase()).distinct().count();
		if (distinct != clean.size()) {
			throw new ApiException(422, "TEMPLATE_STAGE_DUPLICATED",
				"O modelo não pode ter duas etapas com o mesmo nome.");
		}
		return List.copyOf(clean);
	}

	public SelectionProcessTemplate renamed(String newName, Instant now) {
		return new SelectionProcessTemplate(id, ownerId, newName, stageNames, createdAt, now);
	}

	public SelectionProcessTemplate withStages(List<String> newStageNames, Instant now) {
		return new SelectionProcessTemplate(id, ownerId, name, newStageNames, createdAt, now);
	}

	public String id() { return id; }

	public String ownerId() { return ownerId; }

	public String name() { return name; }

	public List<String> stageNames() { return stageNames; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
