package com.socialcoisa.rede.jobs.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.TalentPoolService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;

/**
 * Banco de talentos: o lado do candidato (onde estou liberado) e o lado da organização (quem
 * liberou para mim).
 *
 * <p>Quem é o candidato é sempre quem está na sessão; quem é o recrutador o serviço decide, pela
 * equipe de recrutamento da organização. Nenhuma rota aceita "usuário" vindo do cliente.
 */
@RestController
@RequestMapping("/jobs/recruitment/talent-pool")
class TalentPoolController {

	private final TalentPoolService talentPool;
	private final CurrentUserResolver currentUserResolver;

	TalentPoolController(TalentPoolService talentPool, CurrentUserResolver currentUserResolver) {
		this.talentPool = talentPool;
		this.currentUserResolver = currentUserResolver;
	}

	// ---------- O lado do candidato ----------

	@GetMapping("/mine")
	List<OptInView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return talentPool.mine(requesterId(sessionToken)).stream().map(OptInView::of).toList();
	}

	/** Liberar é idempotente: liberar de novo renova a versão e a data do consentimento. */
	@PostMapping("/{companyId}/opt-in")
	OptInView optIn(@PathVariable String companyId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return OptInView.of(talentPool.optIn(requesterId(sessionToken), companyId));
	}

	/** Retirar sem nada liberado também passa: o estado desejado ("fora do banco") já vale. */
	@DeleteMapping("/{companyId}/opt-in")
	void withdraw(@PathVariable String companyId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		talentPool.withdraw(requesterId(sessionToken), companyId);
	}

	record NoteRequest(String note) {
	}

	@PatchMapping("/{companyId}/note")
	OptInView note(@PathVariable String companyId, @RequestBody(required = false) NoteRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return OptInView.of(
			talentPool.note(requesterId(sessionToken), companyId, body == null ? null : body.note()));
	}

	// ---------- O lado da organização ----------

	@GetMapping("/{companyId}")
	PageResponse<TalentRowView> page(@PathVariable String companyId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "24") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		TalentPoolService.TalentPage page = talentPool.page(companyId, requesterId(sessionToken), cursor, search,
			limit);
		return new PageResponse<>(page.candidates().stream().map(TalentRowView::of).toList(), page.nextCursor());
	}

	private String requesterId(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return user.id().value();
	}

	// ---------- Projeções ----------

	record OptInView(String companyId, String companyName, String consentVersion, String consentedAt,
			String note) {

		static OptInView of(TalentPoolService.OptInView view) {
			return new OptInView(view.companyId(), view.companyName(), view.consentVersion(), view.consentedAt(),
				view.note());
		}
	}

	record TalentRowView(String userId, String name, String username, String headline, String avatar,
			String location, String resumeName, String resumeUrl, int experienceCount, String note) {

		static TalentRowView of(TalentPoolService.TalentRow row) {
			return new TalentRowView(row.userId(), row.name(), row.username(), row.headline(), row.avatar(),
				row.location(), row.resumeName(), row.resumeUrl(), row.experienceCount(), row.note());
		}
	}
}
