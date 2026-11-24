package com.socialcoisa.rede.talentbank.application;

import java.util.List;

import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;

/**
 * A inscrição como as duas partes a veem: o estado, o que foi autorizado e as datas.
 *
 * <p>O que NÃO aparece aqui é o dado do candidato — a inscrição é o consentimento, e o conteúdo
 * autorizado só é servido pela ficha do candidato, e apenas para quem tem a inscrição ativa.</p>
 */
public record TalentBankEnrollmentView(String id, String bankId, String state, List<String> sharedFields,
		String consentVersion, String purposeSnapshot, String note, String requestedAt, String consentedAt,
		String activatedAt, String revokedAt, String closedAt, String erasedAt, String updatedAt) {

	public static TalentBankEnrollmentView of(TalentBankEnrollment enrollment) {
		return new TalentBankEnrollmentView(enrollment.id(), enrollment.bankId(), enrollment.state().name(),
			enrollment.sharedFields(), enrollment.consentVersion(), enrollment.purposeSnapshot(), enrollment.note(),
			enrollment.requestedAt().toString(), instant(enrollment.consentedAt()), instant(enrollment.activatedAt()),
			instant(enrollment.revokedAt()), instant(enrollment.closedAt()), instant(enrollment.erasedAt()),
			enrollment.updatedAt().toString());
	}

	private static String instant(java.time.Instant value) {
		return value == null ? null : value.toString();
	}
}
