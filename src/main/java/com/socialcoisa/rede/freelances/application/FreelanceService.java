package com.socialcoisa.rede.freelances.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.freelances.application.port.out.FreelanceRepository;
import com.socialcoisa.rede.freelances.domain.Freelance;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

/** Freelance: escopo fechado + faixa de valor + prazo. Sem nível/local/vaga externa. */
@Service
public class FreelanceService {

	private static final int PER_HOUR = 10;
	private final FreelanceRepository freelances;
	private final UserProfileRepository profiles;
	private final ActionThrottle throttle;
	private final AgeGate ageGate;
	private final CorporateProfileService corporateProfiles;
	private final MessagingService messaging;
	private final BlockedUsersReader blockedUsers;
	private final Clock clock;

	public FreelanceService(FreelanceRepository freelances, UserProfileRepository profiles,
			ActionThrottle throttle, AgeGate ageGate, CorporateProfileService corporateProfiles,
			MessagingService messaging, BlockedUsersReader blockedUsers, Clock clock) {
		this.freelances = freelances;
		this.profiles = profiles;
		this.throttle = throttle;
		this.ageGate = ageGate;
		this.corporateProfiles = corporateProfiles;
		this.messaging = messaging;
		this.blockedUsers = blockedUsers;
		this.clock = clock;
	}

	private LocalDate validateDeadline(String deadline) {
		LocalDate date;
		try {
			date = LocalDate.parse(deadline == null ? "" : deadline.trim());
		}
		catch (DateTimeParseException invalid) {
			throw new ApiException(400, "INVALID_DEADLINE", "Informe uma data de prazo válida.");
		}
		if (date.isBefore(LocalDate.now(clock))) {
			throw new ApiException(400, "INVALID_DEADLINE", "O prazo precisa ser hoje ou no futuro.");
		}
		return date;
	}

	private void validateContent(String title, String scope, int budgetMin, int budgetMax) {
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "Título do freela precisa de pelo menos 3 caracteres.");
		}
		// Os mesmos tetos das colunas (`freelances.title varchar(200)`, `scope varchar(4000)`...):
		// sem esta checagem o banco recusava a gravacao com "Data too long" e a pessoa recebia 409
		// "Operacao conflita com o estado atual dos dados" — que nao diz qual campo passou do limite.
		TextFieldLimit.check("FREELANCE_FIELD_TOO_LONG", "titulo", title, 200);
		TextFieldLimit.check("FREELANCE_FIELD_TOO_LONG", "escopo", scope, 4000);
		if (!StringUtils.hasText(scope) || scope.trim().length() < 20) {
			throw new ApiException(400, "INVALID_SCOPE",
				"Descreva o escopo fechado em pelo menos 20 caracteres.");
		}
		if (budgetMin <= 0 || budgetMax < budgetMin) {
			throw new ApiException(400, "INVALID_BUDGET", "Informe uma faixa de valor válida.");
		}
	}

	@Transactional
	public Freelance create(String userId, String title, String scope, int budgetMin, int budgetMax,
			String deadline, String paymentMethod) {
		ageGate.requireCanWrite(userId);
		throttle.exigePermitido("freelance", userId, PER_HOUR, java.time.Duration.ofHours(1));
		validateContent(title, scope, budgetMin, budgetMax);
		LocalDate date = validateDeadline(deadline);
		Freelance.PaymentMethod payment = parsePaymentMethod(paymentMethod);
		Freelance f = Freelance.create("free_" + UUID.randomUUID().toString().replace("-", ""), userId,
			title.trim(), scope.trim(), budgetMin, budgetMax, date, payment, clock.instant());
		return freelances.save(f);
	}

	/** Edição pelo dono: mesmo contrato da criação, sem passar pelo gargalo de criação de novo. */
	@Transactional
	public Freelance update(String id, String userId, String title, String scope, int budgetMin, int budgetMax,
			String deadline, String paymentMethod) {
		Freelance f = freelances.findById(id)
			.orElseThrow(() -> new ApiException(404, "FREELANCE_NOT_FOUND", "Freela não encontrado."));
		if (!f.ownedBy(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Este freela não é seu.");
		}
		if (f.status() == Freelance.Status.CLOSED) {
			throw new ApiException(409, "FREELANCE_CLOSED", "Este freela já está encerrado.");
		}
		validateContent(title, scope, budgetMin, budgetMax);
		LocalDate date = validateDeadline(deadline);
		Freelance.PaymentMethod payment = parsePaymentMethod(paymentMethod);
		return freelances.save(f.edit(title.trim(), scope.trim(), budgetMin, budgetMax, date, payment,
			clock.instant()));
	}

	/** Exclusão pelo dono: o freela sai da listagem e de "meus freelas" por inteiro. */
	@Transactional
	public void delete(String id, String userId) {
		Freelance f = freelances.findById(id)
			.orElseThrow(() -> new ApiException(404, "FREELANCE_NOT_FOUND", "Freela não encontrado."));
		if (!f.ownedBy(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Este freela não é seu.");
		}
		freelances.deleteById(id);
	}

	@Transactional
	public Freelance close(String id, String userId) {
		Freelance f = freelances.findById(id)
			.orElseThrow(() -> new ApiException(404, "FREELANCE_NOT_FOUND", "Freela não encontrado."));
		if (!f.ownedBy(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Este freela não é seu.");
		}
		if (f.status() == Freelance.Status.CLOSED) {
			throw new ApiException(409, "ALREADY_CLOSED", "Este freela já está encerrado.");
		}
		return freelances.save(f.close(clock.instant()));
	}

	/**
	 * Proposta para um freela: sai da IDENTIDADE PROFISSIONAL de quem propoe (resolvida pela sessao)
	 * e vai para a conversa FREELANCE, na caixa profissional. Antes o app abria a DM social e a
	 * proposta saia do perfil pessoal. Devolve o id da conversa para o app abrir.
	 */
	@Transactional
	public String propose(String freelanceId, String userId, String message) {
		Freelance f = freelances.findById(freelanceId)
			.orElseThrow(() -> new ApiException(404, "FREELANCE_NOT_FOUND", "Freela não encontrado."));
		if (f.status() != Freelance.Status.OPEN) {
			throw new ApiException(409, "FREELANCE_CLOSED", "Este freela já está encerrado.");
		}
		if (f.ownedBy(userId)) {
			throw new ApiException(400, "CANNOT_PROPOSE_OWN_FREELANCE", "Você não pode enviar proposta para o seu freela.");
		}
		String text = message == null ? "" : message.trim();
		if (text.isEmpty()) {
			throw new ApiException(400, "EMPTY_PROPOSAL", "Escreva a sua proposta.");
		}
		TextFieldLimit.check("FREELANCE_FIELD_TOO_LONG", "proposta", text, 2000);
		corporateProfiles.requireActive(userId);
		if (blockedUsers.hiddenIdsFor(userId).contains(f.userId())) {
			throw new ApiException(403, "USER_BLOCKED", "Não é possível conversar com este usuário.");
		}
		String conversationId = messaging.freelanceConversationBetween(userId, f.userId(), f.title());
		messaging.sendMessage(conversationId, userId, "Proposta para o freela \"" + f.title() + "\"\n\n" + text);
		return conversationId;
	}

	/** Aceita CASH/FAISCAS; sem valor, mantém o circuito da faísca (comportamento antigo). */
	private Freelance.PaymentMethod parsePaymentMethod(String raw) {
		if (!StringUtils.hasText(raw)) {
			return Freelance.PaymentMethod.FAISCAS;
		}
		try {
			return Freelance.PaymentMethod.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException invalid) {
			throw new ApiException(400, "INVALID_PAYMENT_METHOD",
				"Escolha o pagamento em dinheiro ou em faíscas.");
		}
	}

	/**
	 * Busca simples (título e escopo). `%` e `_` são removidos para o termo valer como texto:
	 * a caixa de pesquisa do freela não tem a sintaxe booleana das vagas.
	 */
	@Transactional(readOnly = true)
	public List<Freelance> list(String query, int limit, int offset) {
		String termo = query == null ? null : query.trim().replace("%", "").replace("_", "");
		if (termo != null && termo.isBlank()) {
			termo = null;
		}
		return freelances.findOpen(termo, Math.min(60, Math.max(1, limit)), Math.max(0, offset));
	}

	@Transactional(readOnly = true)
	public List<Freelance> mine(String userId) {
		return freelances.findByUser(userId);
	}

	@Transactional(readOnly = true)
	public String usernameOf(String userId) {
		return profiles.findById(userId).map(p -> p.username()).orElse(null);
	}
}
