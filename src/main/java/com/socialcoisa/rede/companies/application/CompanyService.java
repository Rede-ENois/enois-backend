package com.socialcoisa.rede.companies.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.companies.application.port.out.CompanyRepository;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyMember;
import com.socialcoisa.rede.companies.domain.CompanyRole;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

/**
 * Paginas empresariais. Autorizacao por papel acontece AQUI (nunca no front); dono e unico e a
 * transferencia e explicita. O slug nasce do nome e e unico — colisao ganha sufixo numerico.
 */
@Service
public class CompanyService {

	private final CompanyRepository companies;
	private final NotificationService notificationService;
	private final Clock clock;

	public CompanyService(CompanyRepository companies, NotificationService notificationService, Clock clock) {
		this.companies = companies;
		this.notificationService = notificationService;
		this.clock = clock;
	}

	@Transactional
	public Company create(String userId, String name, String description, String sector, String size, String location,
			String website) {
		if (!StringUtils.hasText(name) || name.trim().length() < 2) {
			throw new ApiException(400, "INVALID_COMPANY_NAME", "Nome da empresa e obrigatorio.");
		}
		checkCampos(name, description, sector, size, location, website);
		String slug = uniqueSlug(slugFrom(name));
		String safeWebsite = StringUtils.hasText(website)
			? com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(website.trim(), "website")
			: null;
		Company company = Company.create("comp_" + UUID.randomUUID().toString().replace("-", ""), slug,
			name.trim(), StringUtils.hasText(description) ? description.trim() : null,
			StringUtils.hasText(sector) ? sector.trim() : null,
			StringUtils.hasText(size) ? size.trim() : null,
			StringUtils.hasText(location) ? location.trim() : null,
			safeWebsite,
			userId, clock.instant());
		Company saved = companies.save(company);
		companies.saveMember(CompanyMember.create("cmemb_" + UUID.randomUUID().toString().replace("-", ""),
			saved.id(), userId, CompanyRole.OWNER, clock.instant()));
		return saved;
	}

	@Transactional
	public Company update(String companyId, String userId, String name, String description, String sector, String size,
			String location, String website, String logo, String cover) {
		Company company = company(companyId);
		requireRole(company, userId, CompanyRole.EDITOR);
		checkCampos(name, description, sector, size, location, website);
		Company updated = company.update(
			StringUtils.hasText(name) ? name.trim() : null,
			StringUtils.hasText(description) ? description.trim() : description,
			sector, size, location,
			StringUtils.hasText(website)
				? com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(website.trim(), "website")
				: website,
			StringUtils.hasText(logo) ? logo.trim() : logo,
			StringUtils.hasText(cover) ? cover.trim() : cover,
			clock.instant());
		return companies.save(updated);
	}

	@Transactional(readOnly = true)
	public Company bySlug(String slug) {
		Company company = companies.findBySlug(slug)
			.orElseThrow(() -> new ApiException(404, "COMPANY_NOT_FOUND", "Pagina empresarial nao encontrada."));
		// Pagina fechada (pela moderacao ou pelo fim dela) nao existe mais para quem visita:
		// sai 404, do mesmo jeito que um perfil apagado.
		if (!company.active()) {
			throw new ApiException(404, "COMPANY_NOT_FOUND", "Pagina empresarial nao encontrada.");
		}
		return company;
	}

	@Transactional(readOnly = true)
	public Company company(String companyId) {
		return companies.findById(companyId)
			.orElseThrow(() -> new ApiException(404, "COMPANY_NOT_FOUND", "Pagina empresarial nao encontrada."));
	}

	@Transactional(readOnly = true)
	public List<Company> memberOf(String userId) {
		return companies.findByMember(userId, 50);
	}

	@Transactional(readOnly = true)
	public List<Company> listAll(int limit) {
		// Diretorio publico: pagina fechada (removida pela moderacao ou pela propria empresa) sai da lista.
		return companies.listAll(limit).stream().filter(Company::active).toList();
	}

	@Transactional(readOnly = true)
	public List<CompanyMember> members(String companyId, String viewerId) {
		// The team is internal to the page: only someone in it, in any role, lists it.
		requireRole(company(companyId), viewerId, CompanyRole.RECRUITER);
		return companies.membersOf(companyId);
	}

	@Transactional(readOnly = true)
	public CompanyRole roleOf(String companyId, String userId) {
		return companies.findMember(companyId, userId).map(CompanyMember::role).orElse(null);
	}

	@Transactional
	public void requireRole(Company company, String userId, CompanyRole minimum) {
		// Pagina fechada nao aceita equipe, vaga nem projeto: a operacao sobre ela morre aqui.
		if (!company.active()) {
			throw new ApiException(409, "COMPANY_CLOSED", "Esta pagina empresarial foi removida.");
		}
		CompanyRole role = roleOf(company.id(), userId);
		if (role == null) {
			throw new ApiException(403, "NOT_A_MEMBER", "Voce nao faz parte desta pagina empresarial.");
		}
		if (company.ownedBy(userId)) {
			return; // dono faz tudo
		}
		boolean enough = switch (minimum) {
			case OWNER -> false;
			case ADMIN -> role == CompanyRole.ADMIN;
			case EDITOR -> role == CompanyRole.ADMIN || role == CompanyRole.EDITOR;
			case RECRUITER -> role == CompanyRole.ADMIN || role == CompanyRole.EDITOR
				|| role == CompanyRole.RECRUITER;
		};
		if (!enough) {
			throw new ApiException(403, "FORBIDDEN", "Seu papel nao permite esta acao.");
		}
	}

	@Transactional
	public CompanyMember addMember(String companyId, String userId, CompanyRole role) {
		Company company = company(companyId);
		// OWNER é o dono da página (owner_user_id), não um papel concedível por convite (E-57).
		if (role == CompanyRole.OWNER) {
			throw new ApiException(400, "INVALID_ROLE", "O papel de proprietario nao pode ser concedido por convite.");
		}
		if (companies.findMember(companyId, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MEMBER", "Esta pessoa ja faz parte da pagina.");
		}
		return companies.saveMember(CompanyMember.create(
			"cmemb_" + UUID.randomUUID().toString().replace("-", ""), company.id(), userId, role, clock.instant()));
	}

	/**
	 * Fecha a pagina por decisao da moderacao: sai do diretorio e deixa de existir para quem
	 * visita. Idempotente — fechar pagina ja fechada nao faz nada e devolve {@code false}.
	 * O dono recebe o aviso (a resposta dele continua pelo canal de recurso da moderacao).
	 */
	@Transactional
	public boolean closeByModeration(String companyId, String reviewerId) {
		Company company = companies.findById(companyId).orElse(null);
		if (company == null || !company.active()) {
			return false;
		}
		companies.save(company.close(clock.instant()));
		notificationService.create(company.ownerUserId(), "MODERACAO", reviewerId,
			"Sua página empresarial foi removida",
			"Uma denúncia sobre a página \"" + company.name()
				+ "\" foi revisada pela moderação e a página foi removida do diretório. "
				+ "Se acredita que foi um engano, fale com o suporte pelo canal de recursos.",
			"/api/suporte", "Falar com o suporte");
		return true;
	}

	/** Pagina por id, inclusive fechada (a moderacao le paginas removidas para revisar). */
	@Transactional(readOnly = true)
	public java.util.Optional<Company> findById(String companyId) {
		return companies.findById(companyId);
	}

	@Transactional(readOnly = true)
	public List<Company> byIds(java.util.Collection<String> ids) {
		return companies.findByIds(ids);
	}

	@Transactional(readOnly = true)
	public List<Company> active(String query, String sector, int limit) {
		return companies.findActive(query, sector, limit);
	}

	@Transactional(readOnly = true)
	public List<String> activeNames() {
		return companies.activeNames();
	}

	/** Distinct sectors of active pages, case variants merged, alphabetical. */
	@Transactional(readOnly = true)
	public List<String> sectors() {
		java.util.Map<String, String> byKey = new java.util.TreeMap<>();
		for (String sector : companies.activeSectors()) {
			if (StringUtils.hasText(sector)) {
				byKey.putIfAbsent(sector.trim().toLowerCase(Locale.ROOT), sector.trim());
			}
		}
		return List.copyOf(byKey.values());
	}

	/** Follow or unfollow (idempotent); returns the follower count after the change. */
	@Transactional
	public long setFollowing(String companyId, String userId, boolean follow) {
		Company company = company(companyId);
		if (follow) {
			companies.follow(company.id(), userId, clock.instant());
		}
		else {
			companies.unfollow(company.id(), userId);
		}
		return companies.followerCount(company.id());
	}

	@Transactional(readOnly = true)
	public boolean isFollowing(String companyId, String userId) {
		return userId != null && companies.isFollowing(companyId, userId);
	}

	@Transactional(readOnly = true)
	public long followerCount(String companyId) {
		return companies.followerCount(companyId);
	}

	@Transactional(readOnly = true)
	public java.util.Map<String, Long> followerCounts(java.util.Collection<String> companyIds) {
		return companies.followerCounts(companyIds);
	}

	/** Tells every follower (except whoever published it) that the page opened something new. */
	@Transactional
	public void notifyFollowers(Company company, String publisherId, String title, String body) {
		// ponytail: one notification row per follower inside the publish transaction; move to an
		// async fan-out if a page ever gathers thousands of followers.
		for (String follower : companies.followerIds(company.id())) {
			if (!follower.equals(publisherId)) {
				notificationService.create(follower, "COMPANY_NEW_OPPORTUNITY", publisherId, title, body,
					"/empresa/" + company.slug(), "Ver");
			}
		}
	}

	private String slugFrom(String name) {
		// Accents become their letter ("Estúdio" -> "estudio"); they used to be dropped ("est-dio").
		return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}

	private String uniqueSlug(String base) {
		String slug = StringUtils.hasText(base) ? base : "empresa";
		String candidate = slug;
		int n = 2;
		while (companies.existsBySlug(candidate)) {
			candidate = slug + "-" + n;
			n++;
		}
		return candidate;
	}

	/**
	 * Os mesmos tetos das colunas (`companies.name varchar(160)`, `sector varchar(80)`...): sem esta
	 * checagem o banco recusava a gravacao com "Data too long" e a pessoa recebia 409 "Operacao
	 * conflita com o estado atual dos dados" — que nao diz qual campo passou do limite.
	 */
	private void checkCampos(String name, String description, String sector, String size, String location,
			String website) {
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "nome", name, 160);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "descricao", description, 4000);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "setor", sector, 80);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "porte", size, 40);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "localizacao", location, 160);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "site", website, 300);
	}
}
