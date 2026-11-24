package com.socialcoisa.rede.talentbank.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.talentbank.application.TalentBankEnrollmentService;
import com.socialcoisa.rede.talentbank.application.TalentBankEnrollmentView;
import com.socialcoisa.rede.talentbank.application.TalentBankEnrollmentService.MyEnrollmentView;
import com.socialcoisa.rede.talentbank.application.TalentBankEventView;
import com.socialcoisa.rede.talentbank.application.TalentBankService;
import com.socialcoisa.rede.talentbank.application.TalentBankService.BankSummary;
import com.socialcoisa.rede.talentbank.application.TalentBankService.DiscoveryPage;
import com.socialcoisa.rede.talentbank.application.TalentBankService.PublicBankView;

/**
 * A vitrine do banco de talentos e o lado da pessoa que se inscreve.
 *
 * <p>Quem está na vitrine é sempre quem está na sessão — e a vitrine nunca mostra os candidatos
 * inscritos: ela mostra as bancas e quem as publica.</p>
 */
@RestController
@RequestMapping("/talent-banks")
class TalentBankController {

	private final TalentBankService banks;
	private final TalentBankEnrollmentService enrollments;
	private final CurrentUserResolver currentUserResolver;

	TalentBankController(TalentBankService banks, TalentBankEnrollmentService enrollments,
			CurrentUserResolver currentUserResolver) {
		this.banks = banks;
		this.enrollments = enrollments;
		this.currentUserResolver = currentUserResolver;
	}

	/** A descoberta: as bancas públicas, com busca e filtro por área. */
	@GetMapping
	PageResponse<BankSummary> discover(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String search, @RequestParam(required = false) String area,
			@RequestParam(defaultValue = "12") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		requester(sessionToken);
		DiscoveryPage page = banks.discover(cursor, search, area, limit);
		return new PageResponse<>(page.banks(), page.nextCursor());
	}

	/** A banca aberta: finalidade, condições de tratamento e quem publica. */
	@GetMapping("/{bankId}")
	PublicBankView detail(@PathVariable String bankId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String viewerId = requester(sessionToken);
		return banks.detail(viewerId, bankId);
	}

	/** Onde a pessoa está inscrita. */
	@GetMapping("/mine/enrollments")
	List<MyEnrollmentView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return enrollments.mine(requester(sessionToken));
	}

	/** O pedido de inscrição: a pessoa marca o que autoriza e a inscrição fica pendente. */
	@PostMapping("/{bankId}/enrollments")
	TalentBankEnrollmentView request(@PathVariable String bankId, @RequestBody(required = false) EnrollmentRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String candidateId = requester(sessionToken);
		return enrollments.request(candidateId, bankId,
			body == null ? List.of() : body.sharedFields(), body == null ? null : body.note());
	}

	/** A confirmação: é ela que libera o acesso do recrutador aos dados autorizados. */
	@PostMapping("/{bankId}/enrollments/{enrollmentId}/confirm")
	TalentBankEnrollmentView confirm(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return enrollments.confirm(requester(sessionToken), bankId, enrollmentId);
	}

	/** A revogação: a pessoa retira a inscrição quando quiser. */
	@DeleteMapping("/{bankId}/enrollments/{enrollmentId}")
	TalentBankEnrollmentView revoke(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return enrollments.revoke(requester(sessionToken), bankId, enrollmentId);
	}

	/** A exclusão pedida pela pessoa: o recado some e o acesso é cortado. */
	@PostMapping("/{bankId}/enrollments/{enrollmentId}/erase")
	TalentBankEnrollmentView erase(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return enrollments.erase(requester(sessionToken), bankId, enrollmentId);
	}

	/** A trilha da inscrição da pessoa. */
	@GetMapping("/enrollments/{enrollmentId}/history")
	List<TalentBankEventView> history(@PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return enrollments.myHistory(requester(sessionToken), enrollmentId);
	}

	private String requester(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return user.id().value();
	}

	/** O que a pessoa autoriza a compartilhar e o recado para o recrutador. */
	record EnrollmentRequest(List<String> sharedFields, String note) {
	}
}
