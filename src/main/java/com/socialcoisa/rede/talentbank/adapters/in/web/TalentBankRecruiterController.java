package com.socialcoisa.rede.talentbank.adapters.in.web;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.talentbank.application.TalentBankCandidateService;
import com.socialcoisa.rede.talentbank.application.TalentBankCandidateService.CandidateDetail;
import com.socialcoisa.rede.talentbank.application.TalentBankCandidateService.CandidatePage;
import com.socialcoisa.rede.talentbank.application.TalentBankCandidateService.CandidateRow;
import com.socialcoisa.rede.talentbank.application.TalentBankEnrollmentView;
import com.socialcoisa.rede.talentbank.application.TalentBankEventView;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankResumeReader.ResumeFile;
import com.socialcoisa.rede.talentbank.application.TalentBankService;
import com.socialcoisa.rede.talentbank.application.TalentBankService.BankCommand;
import com.socialcoisa.rede.talentbank.application.TalentBankService.BankView;

/**
 * O painel do recrutador: o banco dele e as pessoas que se inscreveram.
 *
 * <p>Toda rota aqui é do dono da banca — quem não é recebe a mesma resposta de quem não existe. O
 * currículo é servido pelo backend, com autorização conferida no servidor: o endereço público do
 * arquivo nunca chega ao cliente.</p>
 */
@RestController
@RequestMapping("/talent-banks")
class TalentBankRecruiterController {

	private final TalentBankService banks;
	private final TalentBankCandidateService candidates;
	private final CurrentUserResolver currentUserResolver;

	TalentBankRecruiterController(TalentBankService banks, TalentBankCandidateService candidates,
			CurrentUserResolver currentUserResolver) {
		this.banks = banks;
		this.candidates = candidates;
		this.currentUserResolver = currentUserResolver;
	}

	/** O banco do recrutador. */
	@GetMapping("/owned")
	BankView owned(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String ownerId = requester(sessionToken);
		return banks.myBank(ownerId)
			.orElseThrow(() -> new ApiException(404, "TALENT_BANK_NOT_FOUND",
				"Você ainda não criou o seu banco de talentos."));
	}

	/** A criação do banco: um por recrutador. */
	@PostMapping
	BankView create(@RequestBody BankRequest request,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return banks.create(requester(sessionToken), request.toCommand());
	}

	/** A edição do banco: textos, skills e os campos que ele pede. */
	@PatchMapping("/{bankId}")
	BankView update(@PathVariable String bankId, @RequestBody BankRequest request,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return banks.update(requester(sessionToken), bankId, request.toCommand());
	}

	/** Publicar ou tirar da vitrine. */
	@PatchMapping("/{bankId}/visibility")
	BankView visibility(@PathVariable String bankId, @RequestBody VisibilityRequest request,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Boolean visible = request == null ? null : request.discoverable();
		if (visible == null) {
			throw new ApiException(400, "INVALID_VISIBILITY", "Informe se o banco fica visível.");
		}
		return banks.setVisibility(requester(sessionToken), bankId, visible);
	}

	/** Os inscritos, com busca e filtros por competência, experiência e formação. */
	@GetMapping("/{bankId}/candidates")
	CandidatesPage candidates(@PathVariable String bankId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) String search, @RequestParam(required = false) String state,
			@RequestParam(required = false) String skill, @RequestParam(required = false) Integer minExperiences,
			@RequestParam(required = false) String education, @RequestParam(defaultValue = "24") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CandidatePage page = candidates.page(requester(sessionToken), bankId, cursor, search, state, skill,
			minExperiences, education, limit);
		return new CandidatesPage(page.candidates(), page.nextCursor(), page.activeTotal(), page.pendingTotal());
	}

	/** A ficha de uma pessoa: só o que ela autorizou. */
	@GetMapping("/{bankId}/candidates/{enrollmentId}")
	CandidateDetail candidate(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return candidates.detail(requester(sessionToken), bankId, enrollmentId);
	}

	/** O currículo autorizado, servido pelo backend. */
	@GetMapping("/{bankId}/candidates/{enrollmentId}/resume")
	ResponseEntity<StreamingResponseBody> resume(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		ResumeFile file = candidates.resume(requester(sessionToken), bankId, enrollmentId);
		StreamingResponseBody body = output -> {
			try (var input = file.body()) {
				input.transferTo(output);
			}
		};
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE,
				file.contentType() == null ? MediaType.APPLICATION_PDF_VALUE : file.contentType())
			.header(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(file.fileName()).build().toString())
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.body(body);
	}

	/** O encerramento da inscrição pelo recrutador. */
	@PostMapping("/{bankId}/candidates/{enrollmentId}/close")
	TalentBankEnrollmentView close(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return candidates.close(requester(sessionToken), bankId, enrollmentId);
	}

	/** A trilha da inscrição, vista pelo recrutador. */
	@GetMapping("/{bankId}/candidates/{enrollmentId}/history")
	List<TalentBankEventView> history(@PathVariable String bankId, @PathVariable String enrollmentId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return candidates.history(requester(sessionToken), bankId, enrollmentId);
	}

	private String requester(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return user.id().value();
	}

	/** O que o recrutador escreve no banco. */
	record BankRequest(String title, String purpose, String description, String handlingConditions, String focusArea,
			List<String> desiredSkills, List<String> requestedFields) {

		BankCommand toCommand() {
			return new BankCommand(title, purpose, description, handlingConditions, focusArea, desiredSkills,
				requestedFields);
		}
	}

	/** A escolha de publicar ou não. */
	record VisibilityRequest(Boolean discoverable) {
	}

	/**
	 * A página de inscritos: os itens, o cursor e os totais.
	 *
	 * <p>Os totais vêm junto porque o painel do recrutador mostra quantas inscrições estão ativas e
	 * quantas aguardam confirmação, e isso não pode depender só da página carregada.</p>
	 */
	record CandidatesPage(List<CandidateRow> items, String nextCursor, long activeTotal, long pendingTotal) {
	}
}
