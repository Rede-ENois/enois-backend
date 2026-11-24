package com.socialcoisa.rede.talentbank.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEnrollmentRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEventRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankOwnerDirectory;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankRepository;
import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.SharedField;
import com.socialcoisa.rede.talentbank.domain.TalentBank;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent.Action;

/**
 * O lado da pessoa que se inscreve: pedir, confirmar, revogar e pedir a exclusão.
 *
 * <p>Nada acontece sem a pessoa: o pedido nasce pendente e não libera dado nenhum; a confirmação
 * dela é o que liga o acesso e fixa a versão do consentimento. Revogar corta o acesso na hora, e
 * pedir a exclusão apaga o que a pessoa escreveu — o registro do consentimento continua, porque é
 * ele que dá sustentação à guarda do histórico.</p>
 */
@Service
public class TalentBankEnrollmentService {

	private final TalentBankAccess access;
	private final TalentBankRepository banks;
	private final TalentBankEnrollmentRepository enrollments;
	private final TalentBankEventRepository events;
	private final TalentBankOwnerDirectory owners;
	private final AuditTrail audit;
	private final Clock clock;

	public TalentBankEnrollmentService(TalentBankAccess access, TalentBankRepository banks,
			TalentBankEnrollmentRepository enrollments, TalentBankEventRepository events,
			TalentBankOwnerDirectory owners, AuditTrail audit, Clock clock) {
		this.access = access;
		this.banks = banks;
		this.enrollments = enrollments;
		this.events = events;
		this.owners = owners;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * O pedido de inscrição: a pessoa marca o que autoriza e a inscrição fica pendente.
	 *
	 * <p>Duplicar não existe — pedir de novo a mesma banca enquanto já há pedido ou inscrição ativa
	 * é recusado. Depois de revogar ou de ser encerrado, o pedido é novo: a linha é reaproveitada,
	 * mas o consentimento é o de hoje.</p>
	 */
	@Transactional
	public TalentBankEnrollmentView request(String candidateId, String bankId, List<String> fields, String note) {
		TalentBank bank = access.requireDiscoverable(bankId);
		access.activeOwner(bank.ownerId())
			.orElseThrow(() -> new ApiException(404, "TALENT_BANK_NOT_FOUND", "Banco de talentos não encontrado."));
		List<String> authorized = authorizedFields(bank, fields);
		Instant now = clock.instant();

		TalentBankEnrollment existing = enrollments.findByBankAndCandidate(bank.id(), candidateId).orElse(null);
		if (existing != null && !existing.state().terminal()) {
			throw new ApiException(409, "ALREADY_ENROLLED", "Você já está inscrito neste banco de talentos.");
		}
		TalentBankEnrollment enrollment = existing == null
				? TalentBankEnrollment.request(bank.id(), candidateId, bank.consentVersion(), authorized,
						bank.purpose(), note, now)
				: existing.renew(bank.consentVersion(), authorized, bank.purpose(), note, now);
		TalentBankEnrollment saved = enrollments.save(enrollment);

		events.save(TalentBankEvent.of(bank.id(), saved.id(), candidateId, Action.REQUESTED,
			"campos=" + String.join(",", authorized), now));
		audit.record(candidateId, "TALENT_BANK_ENROLLMENT_REQUESTED", target(bank.id(), saved.id()),
			TalentBankService.json(Map.of("bankId", bank.id(), "state", saved.state().name())));
		return TalentBankEnrollmentView.of(saved);
	}

	/** A confirmação da pessoa: é ela que libera o acesso e fixa a data do consentimento. */
	@Transactional
	public TalentBankEnrollmentView confirm(String candidateId, String bankId, String enrollmentId) {
		TalentBankEnrollment enrollment = requireOwnEnrollment(candidateId, bankId, enrollmentId);
		if (enrollment.state() == EnrollmentState.ACTIVE) {
			return TalentBankEnrollmentView.of(enrollment);
		}
		if (enrollment.state() != EnrollmentState.PENDING) {
			throw new ApiException(409, "ENROLLMENT_NOT_PENDING",
				"Esta inscrição não está esperando confirmação.");
		}
		Instant now = clock.instant();
		TalentBankEnrollment saved = enrollments.save(enrollment.confirm(now));
		events.save(TalentBankEvent.of(saved.bankId(), saved.id(), candidateId, Action.CONFIRMED,
			"campos=" + String.join(",", saved.sharedFields()), now));
		audit.record(candidateId, "TALENT_BANK_ENROLLMENT_CONFIRMED", target(saved.bankId(), saved.id()),
			TalentBankService.json(Map.of("bankId", saved.bankId(), "consentVersion", saved.consentVersion())));
		return TalentBankEnrollmentView.of(saved);
	}

	/** A revogação: o acesso morre agora e o registro continua auditável. */
	@Transactional
	public TalentBankEnrollmentView revoke(String candidateId, String bankId, String enrollmentId) {
		TalentBankEnrollment enrollment = requireOwnEnrollment(candidateId, bankId, enrollmentId);
		if (enrollment.state() == EnrollmentState.REVOKED_BY_CANDIDATE) {
			return TalentBankEnrollmentView.of(enrollment);
		}
		if (enrollment.state() == EnrollmentState.CLOSED_BY_RECRUITER) {
			throw new ApiException(409, "ENROLLMENT_CLOSED",
				"O recrutador encerrou esta inscrição. Você pode pedir uma nova.");
		}
		Instant now = clock.instant();
		TalentBankEnrollment saved = enrollments.save(enrollment.revoke(now));
		events.save(TalentBankEvent.of(saved.bankId(), saved.id(), candidateId, Action.REVOKED_BY_CANDIDATE, null,
			now));
		audit.record(candidateId, "TALENT_BANK_ENROLLMENT_REVOKED", target(saved.bankId(), saved.id()),
			TalentBankService.json(Map.of("bankId", saved.bankId())));
		return TalentBankEnrollmentView.of(saved);
	}

	/**
	 * A exclusão pedida pela pessoa: o recado some e o acesso é cortado. O fato de ter consentido
	 * continua, porque é o que sustenta a guarda do histórico — e a lei permite reter o registro de
	 * consentimento enquanto o resto sai.
	 */
	@Transactional
	public TalentBankEnrollmentView erase(String candidateId, String bankId, String enrollmentId) {
		TalentBankEnrollment enrollment = requireOwnEnrollment(candidateId, bankId, enrollmentId);
		Instant now = clock.instant();
		TalentBankEnrollment current = enrollment;
		if (current.state() == EnrollmentState.ACTIVE || current.state() == EnrollmentState.PENDING) {
			current = current.revoke(now);
		}
		TalentBankEnrollment saved = enrollments.save(current.erase(now));
		events.save(TalentBankEvent.of(saved.bankId(), saved.id(), candidateId, Action.ERASED, null, now));
		audit.record(candidateId, "TALENT_BANK_ENROLLMENT_ERASED", target(saved.bankId(), saved.id()),
			TalentBankService.json(Map.of("bankId", saved.bankId(), "state", saved.state().name())));
		return TalentBankEnrollmentView.of(saved);
	}

	/** Onde a pessoa está inscrita — ela precisa saber em quais bancos entrou. */
	public List<MyEnrollmentView> mine(String candidateId) {
		List<TalentBankEnrollment> mine = enrollments.findByCandidate(candidateId);
		if (mine.isEmpty()) {
			return List.of();
		}
		Map<String, TalentBank> banksById = new LinkedHashMap<>();
		for (TalentBank bank : banks.findAllByIds(mine.stream().map(TalentBankEnrollment::bankId).distinct().toList())) {
			banksById.put(bank.id(), bank);
		}
		Map<String, TalentBankOwnerDirectory.Owner> ownerMap = owners.owners(
			banksById.values().stream().map(TalentBank::ownerId).distinct().toList());
		List<MyEnrollmentView> result = new java.util.ArrayList<>();
		for (TalentBankEnrollment enrollment : mine) {
			TalentBank bank = banksById.get(enrollment.bankId());
			if (bank == null) {
				continue;
			}
			TalentBankOwnerDirectory.Owner owner = ownerMap.get(bank.ownerId());
			result.add(new MyEnrollmentView(enrollment.id(), bank.id(), bank.title(), bank.purpose(),
				owner == null ? null : owner.name(), owner == null ? null : owner.username(),
				enrollment.state().name(), enrollment.sharedFields(), enrollment.consentVersion(),
				enrollment.requestedAt().toString(), instant(enrollment.consentedAt()),
				instant(enrollment.revokedAt()), instant(enrollment.closedAt()), enrollment.updatedAt().toString()));
		}
		return result;
	}

	/** A trilha da inscrição da pessoa: o que aconteceu e quando. */
	public List<TalentBankEventView> myHistory(String candidateId, String enrollmentId) {
		TalentBankEnrollment enrollment = enrollments.findById(enrollmentId)
			.filter(candidate -> candidate.candidateId().equals(candidateId))
			.orElseThrow(() -> new ApiException(404, "ENROLLMENT_NOT_FOUND", "Inscrição não encontrada."));
		return events.findOfEnrollment(enrollment.id()).stream().map(TalentBankEventView::of).toList();
	}

	/** A inscrição da pessoa numa banca. A de outra pessoa é 404 — a mesma de quem não existe. */
	private TalentBankEnrollment requireOwnEnrollment(String candidateId, String bankId, String enrollmentId) {
		return enrollments.findById(enrollmentId)
			.filter(enrollment -> enrollment.candidateId().equals(candidateId))
			.filter(enrollment -> enrollment.bankId().equals(bankId))
			.orElseThrow(() -> new ApiException(404, "ENROLLMENT_NOT_FOUND", "Inscrição não encontrada."));
	}

	/**
	 * Os campos autorizados: um subconjunto do que o recrutador pediu, ao menos um.
	 *
	 * <p>Autorizar um campo que a banca não pediu seria consentir com algo que ninguém esperava —
	 * e a tela de consentimento mostra exatamente a lista pedida.</p>
	 */
	private List<String> authorizedFields(TalentBank bank, List<String> fields) {
		if (fields == null || fields.isEmpty()) {
			throw new ApiException(400, "INVALID_SHARED_FIELD",
				"Escolha quais informações profissionais quer compartilhar.");
		}
		Set<String> requested = new LinkedHashSet<>(bank.requestedFields());
		Set<String> chosen = new LinkedHashSet<>();
		for (String value : fields) {
			SharedField field = SharedField.byKey(value)
				.orElseThrow(() -> new ApiException(400, "INVALID_SHARED_FIELD",
					"Informação profissional desconhecida."));
			if (!requested.contains(field.key())) {
				throw new ApiException(400, "INVALID_SHARED_FIELD",
					"Este banco não pediu a informação \"" + field.key() + "\".");
			}
			chosen.add(field.key());
		}
		return List.copyOf(chosen);
	}

	/** A inscrição da pessoa, com o estado da banca em que ela está. */
	public record MyEnrollmentView(String enrollmentId, String bankId, String bankTitle, String bankPurpose,
			String recruiterName, String recruiterUsername, String state, List<String> sharedFields,
			String consentVersion, String requestedAt, String consentedAt, String revokedAt, String closedAt,
			String updatedAt) {
	}

	private static String instant(Instant value) {
		return value == null ? null : value.toString();
	}

	private static String target(String bankId, String enrollmentId) {
		return "talent_bank:" + bankId + ":" + enrollmentId;
	}
}
