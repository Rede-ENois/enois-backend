package com.socialcoisa.rede.talentbank.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A vitrine de recrutamento de uma pessoa recrutadora.
 *
 * <p>Quem publica é o dono da banca, e só ele a administra: nenhuma outra conta enxerga as
 * inscrições nem altera os dados dela. A banca declara o que procura, com que finalidade e sob
 * quais condições os dados do candidato serão tratados — o texto que a pessoa lê antes de
 * autorizar é este.</p>
 *
 * <p>Desligar o modo "Sou recrutador" não apaga nada: a banca sai da descoberta (a elegibilidade
 * vem do dono, não de uma cópia do perfil) e volta intacta quando o modo volta, com a mesma
 * configuração de antes.</p>
 */
public class TalentBank {

	/** A versão do termo de consentimento aceito; muda junto com o texto, nunca antes. */
	public static final String CONSENT_VERSION = "2026-11";

	public static final int MAX_TITLE = 120;
	public static final int MAX_PURPOSE = 600;
	public static final int MAX_DESCRIPTION = 1500;
	public static final int MAX_CONDITIONS = 1500;
	public static final int MAX_FOCUS_AREA = 120;
	public static final int MAX_SKILLS = 20;
	public static final int MAX_SKILL_LENGTH = 40;

	private final String id;
	private final String ownerId;
	private final String title;
	private final String purpose;
	private final String description;
	private final String handlingConditions;
	private final String focusArea;
	private final List<String> desiredSkills;
	private final List<String> requestedFields;
	private final String consentVersion;
	private final boolean discoverable;
	private final Instant createdAt;
	private final Instant updatedAt;

	private TalentBank(String id, String ownerId, String title, String purpose, String description,
			String handlingConditions, String focusArea, List<String> desiredSkills, List<String> requestedFields,
			String consentVersion, boolean discoverable, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.ownerId = ownerId;
		this.title = title;
		this.purpose = purpose;
		this.description = description;
		this.handlingConditions = handlingConditions;
		this.focusArea = focusArea;
		this.desiredSkills = desiredSkills;
		this.requestedFields = requestedFields;
		this.consentVersion = consentVersion;
		this.discoverable = discoverable;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static TalentBank create(String ownerId, String title, String purpose, String description,
			String handlingConditions, String focusArea, List<String> desiredSkills, List<String> requestedFields,
			boolean discoverable, Instant now) {
		return new TalentBank(UUID.randomUUID().toString(), ownerId, title, purpose, description,
			handlingConditions, focusArea, List.copyOf(desiredSkills), List.copyOf(requestedFields), CONSENT_VERSION,
			discoverable, now, now);
	}

	public static TalentBank restore(String id, String ownerId, String title, String purpose, String description,
			String handlingConditions, String focusArea, List<String> desiredSkills, List<String> requestedFields,
			String consentVersion, boolean discoverable, Instant createdAt, Instant updatedAt) {
		return new TalentBank(id, ownerId, title, purpose, description, handlingConditions, focusArea,
			List.copyOf(desiredSkills), List.copyOf(requestedFields), consentVersion, discoverable, createdAt, updatedAt);
	}

	/** O conteúdo muda, a identidade e o dono não: a banca continua sendo a mesma para quem-inscreveu. */
	public TalentBank withDetails(String title, String purpose, String description, String handlingConditions,
			String focusArea, List<String> desiredSkills, List<String> requestedFields, Instant now) {
		return new TalentBank(id, ownerId, title, purpose, description, handlingConditions, focusArea,
			List.copyOf(desiredSkills), List.copyOf(requestedFields), consentVersion, discoverable, createdAt, now);
	}

	/** A visibilidade pública é uma escolha do dono, separada do consentimento de cada candidato. */
	public TalentBank withDiscoverable(boolean visible, Instant now) {
		return new TalentBank(id, ownerId, title, purpose, description, handlingConditions, focusArea, desiredSkills,
			requestedFields, consentVersion, visible, createdAt, now);
	}

	public String id() {
		return id;
	}

	public String ownerId() {
		return ownerId;
	}

	public String title() {
		return title;
	}

	public String purpose() {
		return purpose;
	}

	public String description() {
		return description;
	}

	public String handlingConditions() {
		return handlingConditions;
	}

	public String focusArea() {
		return focusArea;
	}

	public List<String> desiredSkills() {
		return desiredSkills;
	}

	/** Os campos que o recrutador pede — o candidato autoriza um subconjunto deles. */
	public List<String> requestedFields() {
		return requestedFields;
	}

	public String consentVersion() {
		return consentVersion;
	}

	public boolean discoverable() {
		return discoverable;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
