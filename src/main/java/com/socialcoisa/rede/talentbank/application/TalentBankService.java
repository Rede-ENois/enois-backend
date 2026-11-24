package com.socialcoisa.rede.talentbank.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEnrollmentRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEventRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankOwnerDirectory;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankOwnerDirectory.Owner;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankRepository;
import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.SharedField;
import com.socialcoisa.rede.talentbank.domain.TalentBank;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent.Action;

/**
 * O banco de talentos individual: a vitrine de recrutamento de cada recrutador.
 *
 * <p>Duas permissões vivem aqui e não se confundem: quem pode ADMINISTRAR a banca (o dono, com o
 * modo recrutador ligado) e quem pode VER os dados de quem se inscreveu (o recrutador, a inscrição
 * ativa e o campo autorizado — nada além disso).</p>
 *
 * <p>O modo recrutador desligado tira a banca da descoberta e suspende a administração, sem apagar
 * banca, inscrições nem histórico: religar o modo devolve tudo como estava.</p>
 */
@Service
public class TalentBankService {

	static final int MAX_PAGE_SIZE = 24;
	static final int MAX_SEARCH_LENGTH = 120;
	static final int MAX_AREA_LENGTH = 120;

	private final TalentBankRepository banks;
	private final TalentBankEnrollmentRepository enrollments;
	private final TalentBankOwnerDirectory owners;
	private final TalentBankAccess access;
	private final TalentBankEventRepository events;
	private final AuditTrail audit;
	private final Clock clock;

	public TalentBankService(TalentBankRepository banks, TalentBankEnrollmentRepository enrollments,
			TalentBankOwnerDirectory owners, TalentBankAccess access, TalentBankEventRepository events, AuditTrail audit,
			Clock clock) {
		this.banks = banks;
		this.enrollments = enrollments;
		this.owners = owners;
		this.access = access;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	// ---------- O banco do recrutador ----------

	/** O banco do recrutador — um por pessoa. */
	public Optional<BankView> myBank(String ownerId) {
		return access.ownedBank(ownerId).map(this::view);
	}

	@Transactional
	public BankView create(String ownerId, BankCommand command) {
		access.requireRecruiter(ownerId);
		if (!banks.findByOwner(ownerId).isEmpty()) {
			throw new ApiException(409, "TALENT_BANK_EXISTS",
				"Você já tem um banco de talentos. Edite o que já existe.");
		}
		Content content = validate(command);
		Instant now = clock.instant();
		TalentBank bank = TalentBank.create(ownerId, content.title(), content.purpose(), content.description(),
			content.handlingConditions(), content.focusArea(), content.desiredSkills(), content.requestedFields(),
			false, now);
		TalentBank saved = banks.save(bank);
		events.save(TalentBankEvent.of(saved.id(), null, ownerId, Action.BANK_CREATED,
			"campos=" + String.join(",", saved.requestedFields()), now));
		audit.record(ownerId, "TALENT_BANK_CREATED", target(saved.id()),
			json(Map.of("title", saved.title(), "discoverable", "false")));
		return view(saved);
	}

	@Transactional
	public BankView update(String ownerId, String bankId, BankCommand command) {
		TalentBank bank = requireOwnedBank(ownerId, bankId);
		Content content = validate(command);
		Instant now = clock.instant();
		TalentBank saved = banks.save(bank.withDetails(content.title(), content.purpose(), content.description(),
			content.handlingConditions(), content.focusArea(), content.desiredSkills(), content.requestedFields(),
			now));
		events.save(TalentBankEvent.of(saved.id(), null, ownerId, Action.BANK_UPDATED,
			"campos=" + String.join(",", saved.requestedFields()), now));
		audit.record(ownerId, "TALENT_BANK_UPDATED", target(saved.id()), json(Map.of("title", saved.title())));
		return view(saved);
	}

	/**
	 * Publicar ou tirar da vitrine. É uma escolha do dono e não tem nada a ver com o consentimento
	 * de cada candidato: uma banca pública não torna público o currículo de quem se inscreveu.
	 */
	@Transactional
	public BankView setVisibility(String ownerId, String bankId, boolean visible) {
		TalentBank bank = requireOwnedBank(ownerId, bankId);
		Instant now = clock.instant();
		TalentBank saved = banks.save(bank.withDiscoverable(visible, now));
		events.save(TalentBankEvent.of(saved.id(), null, ownerId, Action.VISIBILITY_CHANGED,
			"visivel=" + visible, now));
		audit.record(ownerId, "TALENT_BANK_VISIBILITY_CHANGED", target(saved.id()),
			json(Map.of("discoverable", String.valueOf(visible))));
		return view(saved);
	}

	// ---------- A vitrine ----------

	/**
	 * A descoberta: as bancas públicas, da mais nova para a mais antiga.
	 *
	 * <p>Só entra na vitrine a banca de quem está no modo recrutador — desligar o modo retira a
	 * banca de cena sem apagá-la. E a vitrine nunca mostra quem se inscreveu: os candidatos não
	 * aparecem para quem apenas está procurando.</p>
	 */
	public DiscoveryPage discover(String cursor, String search, String area, int limit) {
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		String term = trim(search, MAX_SEARCH_LENGTH);
		String areaTerm = trim(area, MAX_AREA_LENGTH);
		TalentBankCursor.Cursor decoded = TalentBankCursor.decode(cursor);
		List<TalentBank> raw = banks.pageDiscoverableMatching(term, areaTerm, decoded.instant(), decoded.id(),
			size + 1);
		boolean hasMore = raw.size() > size;

		Map<String, Owner> ownerMap = owners.owners(raw.stream().map(TalentBank::ownerId).distinct().toList());
		List<BankSummary> items = new ArrayList<>();
		for (TalentBank bank : raw) {
			Owner owner = ownerMap.get(bank.ownerId());
			if (owner != null && owner.recruiter()) {
				items.add(summary(bank, owner));
			}
		}
		TalentBank last = raw.isEmpty() ? null : raw.get(raw.size() - 1);
		String nextCursor = hasMore && last != null ? TalentBankCursor.encode(last.createdAt(), last.id()) : null;
		return new DiscoveryPage(items, nextCursor);
	}

	/** A página pública de uma banca: o que o candidato lê antes de autorizar. */
	public PublicBankView detail(String viewerId, String bankId) {
		TalentBank bank = access.requireDiscoverable(bankId);
		Owner owner = owners.owners(List.of(bank.ownerId())).get(bank.ownerId());
		if (owner == null || !owner.recruiter()) {
			throw new ApiException(404, "TALENT_BANK_NOT_FOUND", "Banco de talentos não encontrado.");
		}
		Optional<TalentBankEnrollment> mine = enrollments.findByBankAndCandidate(bank.id(), viewerId);
		return new PublicBankView(bank.id(), bank.title(), bank.purpose(), bank.description(),
			bank.handlingConditions(), bank.focusArea(), bank.desiredSkills(), bank.requestedFields(),
			bank.consentVersion(), bank.createdAt().toString(),
			new RecruiterView(owner.userId(), owner.name(), owner.username(), owner.avatar(), owner.headline(),
				owner.location()),
			mine.map(TalentBankService::enrollmentState).orElse(null));
	}

	// ---------- Views ----------

	/** O banco como o dono o administra. */
	public record BankView(String id, String title, String purpose, String description, String handlingConditions,
			String focusArea, List<String> desiredSkills, List<String> requestedFields, String consentVersion,
			boolean discoverable, long activeEnrollments, String createdAt, String updatedAt) {
	}

	/** A banca na vitrine: quem publica, o que procura e com que finalidade. */
	public record BankSummary(String id, String title, String purpose, String focusArea, List<String> desiredSkills,
			List<String> requestedFields, String createdAt, RecruiterView recruiter) {
	}

	/** A banca aberta por quem está pensando em se inscrever. */
	public record PublicBankView(String id, String title, String purpose, String description,
			String handlingConditions, String focusArea, List<String> desiredSkills, List<String> requestedFields,
			String consentVersion, String createdAt, RecruiterView recruiter, String myState) {
	}

	/** Como o recrutador se apresenta na vitrine. */
	public record RecruiterView(String userId, String name, String username, String avatar, String headline,
			String location) {
	}

	public record DiscoveryPage(List<BankSummary> banks, String nextCursor) {
	}

	/** O que o recrutador escreve no banco. */
	public record BankCommand(String title, String purpose, String description, String handlingConditions,
			String focusArea, List<String> desiredSkills, List<String> requestedFields) {
	}

	// ---------- Regras ----------

	/**
	 * Quem administra a banca é o dono com o modo recrutador ligado. O modo desligado suspende o
	 * acesso sem apagar nada — e religá-lo devolve a banca como estava.
	 */
	private TalentBank requireOwnedBank(String ownerId, String bankId) {
		return access.requireOwnedBank(ownerId, bankId);
	}

	private BankView view(TalentBank bank) {
		return new BankView(bank.id(), bank.title(), bank.purpose(), bank.description(), bank.handlingConditions(),
			bank.focusArea(), bank.desiredSkills(), bank.requestedFields(), bank.consentVersion(),
			bank.discoverable(), enrollments.countByBankAndState(bank.id(), EnrollmentState.ACTIVE),
			bank.createdAt().toString(), bank.updatedAt().toString());
	}

	private BankSummary summary(TalentBank bank, Owner owner) {
		return new BankSummary(bank.id(), bank.title(), bank.purpose(), bank.focusArea(), bank.desiredSkills(),
			bank.requestedFields(), bank.createdAt().toString(),
			new RecruiterView(owner.userId(), owner.name(), owner.username(), owner.avatar(), owner.headline(),
				owner.location()));
	}

	static String enrollmentState(TalentBankEnrollment enrollment) {
		return enrollment.state().name();
	}

	// ---------- Validação do conteúdo ----------

	/** O conteúdo limpo e conferido: tamanho, campos pedidos e skills. */
	record Content(String title, String purpose, String description, String handlingConditions, String focusArea,
			List<String> desiredSkills, List<String> requestedFields) {
	}

	private Content validate(BankCommand command) {
		String title = require(command == null ? null : command.title(), 3, TalentBank.MAX_TITLE, "INVALID_TITLE",
			"Dê um nome ao banco de talentos (entre 3 e " + TalentBank.MAX_TITLE + " caracteres).");
		String purpose = require(command.purpose(), 10, TalentBank.MAX_PURPOSE, "INVALID_PURPOSE",
			"Descreva a finalidade do banco de talentos (entre 10 e " + TalentBank.MAX_PURPOSE + " caracteres).");
		String conditions = require(command.handlingConditions(), 10, TalentBank.MAX_CONDITIONS,
			"INVALID_CONDITIONS",
			"Informe as condições de tratamento dos dados (entre 10 e " + TalentBank.MAX_CONDITIONS + " caracteres).");
		String description = optional(command.description(), TalentBank.MAX_DESCRIPTION, "INVALID_DESCRIPTION",
			"A descrição do banco é longa demais.");
		String focusArea = optional(command.focusArea(), TalentBank.MAX_FOCUS_AREA, "INVALID_FOCUS_AREA",
			"A área de atuação é longa demais.");
		return new Content(title, purpose, description, conditions, focusArea, skills(command.desiredSkills()),
			fields(command.requestedFields()));
	}

	/** As skills buscadas: no máximo vinte, sem vírgula (o separador do banco). */
	private List<String> skills(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		if (raw.size() > TalentBank.MAX_SKILLS) {
			throw new ApiException(400, "INVALID_SKILL",
				"Escolha no máximo " + TalentBank.MAX_SKILLS + " skills.");
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String value : raw) {
			String skill = value == null ? "" : value.trim();
			if (skill.isEmpty() || skill.length() > TalentBank.MAX_SKILL_LENGTH || skill.contains(",")) {
				throw new ApiException(400, "INVALID_SKILL", "Skill inválida.");
			}
			unique.add(skill);
		}
		return List.copyOf(unique);
	}

	/** Os campos que o recrutador pede: ao menos um, e todos precisam existir. */
	private List<String> fields(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			throw new ApiException(400, "INVALID_SHARED_FIELD",
				"Escolha quais informações profissionais quer receber.");
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String value : raw) {
			SharedField field = SharedField.byKey(value)
				.orElseThrow(() -> new ApiException(400, "INVALID_SHARED_FIELD", "Informação profissional desconhecida."));
			unique.add(field.key());
		}
		return List.copyOf(unique);
	}

	private static String require(String value, int min, int max, String code, String message) {
		String text = value == null ? "" : value.trim();
		if (text.length() < min || text.length() > max) {
			throw new ApiException(400, code, message);
		}
		return text;
	}

	private static String optional(String value, int max, String code, String message) {
		String text = value == null ? "" : value.trim();
		if (text.isEmpty()) {
			return null;
		}
		if (text.length() > max) {
			throw new ApiException(400, code, message);
		}
		return text;
	}

	private static String trim(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String text = value.trim();
		return text.length() > max ? text.substring(0, max) : text;
	}

	private static String target(String bankId) {
		return "talent_bank:" + bankId;
	}

	static String json(Map<String, String> metadata) {
		Map<String, String> ordered = new LinkedHashMap<>(metadata);
		StringBuilder out = new StringBuilder("{");
		ordered.forEach((key, value) -> {
			if (out.length() > 1) {
				out.append(',');
			}
			out.append('"').append(key).append("\":\"").append(escape(value)).append('"');
		});
		return out.append('}').toString();
	}

	private static String escape(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
