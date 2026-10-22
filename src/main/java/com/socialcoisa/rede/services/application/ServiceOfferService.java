package com.socialcoisa.rede.services.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.games.application.GameUrlGuard;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.services.application.port.out.ServiceOfferRepository;
import com.socialcoisa.rede.services.domain.ServiceCategory;
import com.socialcoisa.rede.services.domain.ServiceOffer;
import com.socialcoisa.rede.services.domain.ServiceStatus;
import com.socialcoisa.rede.shared.web.ApiException;

import jakarta.transaction.Transactional;

/**
 * Catálogo de serviços da comunidade.
 *
 * Mesma mecânica dos jogos: a pessoa envia, a moderação publica ou recusa, o dono edita e
 * reenvia. O link é opcional e validado pelo mesmo guard dos jogos — https, host público,
 * sem credenciais na URL.
 */
@Service
public class ServiceOfferService {

	private static final int MAX_TITLE = 120;
	private static final int MAX_DESCRIPTION = 4000;
	private static final int MAX_LINK = 500;

	private final ServiceOfferRepository repository;
	private final UserProfileRepository profiles;
	private final GameUrlGuard urlGuard;
	private final java.time.Clock clock;

	public ServiceOfferService(ServiceOfferRepository repository, UserProfileRepository profiles,
			GameUrlGuard urlGuard, java.time.Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.urlGuard = urlGuard;
		this.clock = clock;
	}

	@Transactional
	public ServiceOffer submit(String userId, String title, String description, String link,
			String category) {
		ServiceCategory parsedCategory = parseCategory(category);
		String safeTitle = requireTitle(title);
		String safeDescription = requireDescription(description);
		String safeLink = validateLink(link);
		String slug = uniqueSlug(userId, slugFrom(safeTitle));
		ServiceOffer offer = ServiceOffer.submit(ServiceOffer.newId(), userId, slug, safeTitle, safeDescription,
			safeLink, parsedCategory, clock.instant());
		return repository.save(offer);
	}

	@Transactional
	public ServiceOffer edit(String serviceId, String userId, String title, String description, String link,
			String category) {
		ServiceOffer offer = owned(serviceId, userId);
		ServiceCategory parsedCategory = parseCategory(category);
		String safeLink = validateLink(link);
		ServiceOffer edited = offer.withContent(requireTitle(title), requireDescription(description), safeLink,
			parsedCategory, clock.instant());
		return repository.save(edited);
	}

	@Transactional
	public void delete(String serviceId, String userId) {
		repository.delete(owned(serviceId, userId));
	}

	/** Catálogo público: apenas publicados, com filtro opcional de categoria e busca. */
	@Transactional
	public List<ServiceOffer> catalog(String category, String query, int limit) {
		return repository.findByStatusOrderByCreatedAtDesc(ServiceStatus.PUBLISHED).stream()
			.filter(offer -> !StringUtils.hasText(category)
				|| offer.category() == parseCategory(category))
			.filter(offer -> matches(offer, query))
			.limit(Math.max(1, Math.min(limit, 100)))
			.toList();
	}

	/** O dono abre o próprio serviço em qualquer estado; os demais, só o publicado. */
	@Transactional
	public Optional<ServiceOffer> byUserAndSlug(String username, String slug, String viewerId) {
		String userId = profiles.findByUsername(username)
			.map(UserProfile::id)
			.orElseThrow(() -> new ApiException(404, "SERVICE_NOT_FOUND", "Serviço não encontrado."));
		return repository.findByUserIdAndSlug(userId, slug)
			.filter(offer -> offer.status() == ServiceStatus.PUBLISHED
				|| (viewerId != null && offer.ownedBy(viewerId)));
	}

	@Transactional
	public List<ServiceOffer> mine(String userId) {
		return repository.findByUserOrderByUpdatedAtDesc(userId);
	}

	@Transactional
	public List<ServiceOffer> pending(int limit) {
		return repository.findByStatusOrderByCreatedAtDesc(ServiceStatus.SUBMITTED).stream()
			.limit(Math.max(1, Math.min(limit, 100)))
			.toList();
	}

	@Transactional
	public ServiceOffer approve(String serviceId, String reviewerId, String note) {
		ServiceOffer offer = byId(serviceId);
		return repository.save(offer.approve(reviewerId, StringUtils.hasText(note) ? note.trim() : null,
			clock.instant()));
	}

	@Transactional
	public ServiceOffer reject(String serviceId, String reviewerId, String note) {
		ServiceOffer offer = byId(serviceId);
		if (!StringUtils.hasText(note)) {
			throw new ApiException(400, "REASON_REQUIRED", "Diga ao prestador o motivo da recusa.");
		}
		return repository.save(offer.reject(reviewerId, note.trim(), clock.instant()));
	}

	@Transactional
	public ServiceOffer suspend(String serviceId, String reviewerId, String note) {
		ServiceOffer offer = byId(serviceId);
		return repository.save(offer.suspend(reviewerId, StringUtils.hasText(note) ? note.trim() : null,
			clock.instant()));
	}

	@Transactional
	public ServiceOffer resubmit(String serviceId, String userId) {
		ServiceOffer offer = owned(serviceId, userId);
		if (offer.status() != ServiceStatus.REJECTED) {
			throw new ApiException(409, "NOT_REJECTED", "Somente serviço recusado pode ser reenviado.");
		}
		return repository.save(offer.resubmit(clock.instant()));
	}

	/** Mesmo conflito de interesse dos jogos: moderador nao aprova o proprio servico. */
	public void requireNotOwnSubmission(String serviceId, String reviewerId) {
		if (byId(serviceId).ownedBy(reviewerId)) {
			throw new ApiException(403, "OWN_SUBMISSION", "Quem modera não aprova o próprio serviço.");
		}
	}

	private ServiceOffer byId(String serviceId) {
		return repository.findById(serviceId)
			.orElseThrow(() -> new ApiException(404, "SERVICE_NOT_FOUND", "Serviço não encontrado."));
	}

	private ServiceOffer owned(String serviceId, String userId) {
		ServiceOffer offer = byId(serviceId);
		if (!offer.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Somente o prestador pode fazer isso.");
		}
		return offer;
	}

	/** O endereço público do serviço usa o username do perfil (o @ do prestador). */
	public String usernameOf(ServiceOffer offer) {
		return profiles.findById(offer.userId()).map(UserProfile::username).orElse(null);
	}

	private boolean matches(ServiceOffer offer, String query) {
		if (!StringUtils.hasText(query)) {
			return true;
		}
		String needle = query.trim().toLowerCase(Locale.ROOT);
		return offer.title().toLowerCase(Locale.ROOT).contains(needle)
			|| offer.description().toLowerCase(Locale.ROOT).contains(needle);
	}

	private String requireTitle(String title) {
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "O título precisa de pelo menos 3 caracteres.");
		}
		if (title.trim().length() > MAX_TITLE) {
			throw new ApiException(400, "INVALID_TITLE", "O título passou do limite de 120 caracteres.");
		}
		return title.trim();
	}

	private String requireDescription(String description) {
		if (!StringUtils.hasText(description) || description.trim().length() < 10) {
			throw new ApiException(400, "INVALID_DESCRIPTION",
				"Descreva o serviço em pelo menos 10 caracteres.");
		}
		if (description.trim().length() > MAX_DESCRIPTION) {
			throw new ApiException(400, "INVALID_DESCRIPTION",
				"A descrição passou do limite de 4.000 caracteres.");
		}
		return description.trim();
	}

	private String validateLink(String link) {
		if (!StringUtils.hasText(link)) {
			return null;
		}
		String safeLink = urlGuard.validate(link);
		if (safeLink.length() > MAX_LINK) {
			throw new ApiException(400, "INVALID_LINK", "O link passou do limite de 500 caracteres.");
		}
		return safeLink;
	}

	private ServiceCategory parseCategory(String category) {
		try {
			return ServiceCategory.valueOf(category == null ? "" : category.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(400, "INVALID_CATEGORY", "Escolha uma categoria válida.");
		}
	}

	/** Mesma regra dos jogos: acento vira letra e o que não é [a-z0-9] vira hífen. */
	private String slugFrom(String value) {
		return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}

	private String uniqueSlug(String userId, String base) {
		String slug = StringUtils.hasText(base) ? base : "servico";
		String candidate = slug;
		int n = 2;
		while (repository.findByUserIdAndSlug(userId, candidate).isPresent()) {
			candidate = slug + "-" + n;
			n++;
		}
		return candidate;
	}
}
