package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Uma inscrição para servir um nó: o pedido público que a administração decide.
 *
 * <p>O ciclo de vida é curto e explícito: {@code PENDING} → {@code APPROVED} ou {@code REJECTED},
 * sem volta. Aprovada, a inscrição passa a carregar o token do link de e-mail (só o hash) e a
 * expiração; a chave de provisionamento em si <b>não existe</b> até alguém abrir o link, e é por isso
 * que renovar o link é seguro: ele não revela uma credencial antiga.</p>
 *
 * <p>{@code revealConsumedAt} é o uso único. Depois dele, o mesmo link responde como já usado — não
 * há reemissão do mesmo link nem da mesma chave. É o comportamento que o formulário promete por
 * escrito no contrato (cláusula 8).</p>
 */
public record NetworkJoinRequest(String id, String fullName, String email, String rg, String cpf,
		String motivation, String termVersion, Instant acceptedAt, Status status, String profiles,
		String decidedBy, Instant decidedAt, String decisionNote, String revealTokenHash,
		Instant revealExpiresAt, Instant revealConsumedAt, Instant emailSentAt, Instant createdAt,
		Instant updatedAt) {

	/** Estado da decisão administrativa. */
	public enum Status {
		PENDING,
		APPROVED,
		REJECTED
	}

	public boolean pending() {
		return status == Status.PENDING;
	}

	/** O link pode ser aberto agora? Aprovado, não consumido e dentro da validade. */
	public boolean revealUsable(Instant now) {
		return status == Status.APPROVED && revealConsumedAt == null && revealTokenHash != null
			&& revealExpiresAt != null && revealExpiresAt.isAfter(now);
	}

	/** A decisão administrativa. {@code reveal} só existe quando a decisão é a aprovação. */
	public NetworkJoinRequest decide(Status decided, String actorId, String note, String chosenProfiles,
			String revealHash, Instant revealExpiresAt, Instant now) {
		return new NetworkJoinRequest(id, fullName, email, rg, cpf, motivation, termVersion, acceptedAt, decided,
			chosenProfiles, actorId, now, note, revealHash, revealExpiresAt, null, emailSentAt, createdAt, now);
	}

	/** Novo link de exibição: invalida o anterior (o hash muda) enquanto nenhum foi usado. */
	public NetworkJoinRequest withReveal(String newHash, Instant expiresAt, Instant now) {
		return new NetworkJoinRequest(id, fullName, email, rg, cpf, motivation, termVersion, acceptedAt, status,
			profiles, decidedBy, decidedAt, decisionNote, newHash, expiresAt, null, emailSentAt, createdAt, now);
	}

	/** O link foi aberto e a chave exibida; o hash permanece só como registro de que houve uso. */
	public NetworkJoinRequest consumed(Instant now) {
		return new NetworkJoinRequest(id, fullName, email, rg, cpf, motivation, termVersion, acceptedAt, status,
			profiles, decidedBy, decidedAt, decisionNote, revealTokenHash, revealExpiresAt, now, emailSentAt,
			createdAt, now);
	}

	public NetworkJoinRequest emailed(Instant now) {
		return new NetworkJoinRequest(id, fullName, email, rg, cpf, motivation, termVersion, acceptedAt, status,
			profiles, decidedBy, decidedAt, decisionNote, revealTokenHash, revealExpiresAt, revealConsumedAt, now,
			createdAt, now);
	}

	/** CPF como o painel mostra em lista. O valor completo só sai no detalhe administrativo. */
	public String maskedCpf() {
		return "***.***.***-" + (cpf != null && cpf.length() == 11 ? cpf.substring(9) : "");
	}

	/** RG mascarado: só os dois últimos caracteres aparecem. */
	public String maskedRg() {
		if (rg == null || rg.length() <= 2) {
			return "***";
		}
		return "*".repeat(rg.length() - 2) + rg.substring(rg.length() - 2);
	}
}