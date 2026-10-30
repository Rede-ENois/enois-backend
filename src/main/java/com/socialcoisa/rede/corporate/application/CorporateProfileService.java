package com.socialcoisa.rede.corporate.application;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Serviço do perfil profissional (o alterego corporativo da conta).
 *
 * Regras que o domínio já garante e este serviço orquestra:
 * - o perfil nasce por iniciativa do usuário, SEMPRE em rascunho e SEM NADA copiado do perfil
 *   social (a importação com revisão é um fluxo separado);
 * - o @ profissional vive em namespace próprio, com unicidade no banco (o repositório traduce
 *   violação em 409);
 * - ativar exige os campos mínimos preenchidos (nome e headline) para que um perfil vazio ou
 *   incompleto nunca apareça como pronto;
 * - desativar não apaga nada.
 */
@Service
public class CorporateProfileService {

	/** Campos que podem vir do perfil social — por nome, nunca em bloco. */
	public static final List<String> IMPORTABLE_FIELDS = List.of(
		"name", "headline", "bio", "avatar", "banner", "bannerHtml", "location");

	/**
	 * O conjunto de nomes aceitos é comparado SEMPRE em minúsculas (o cliente manda o nome do
	 * campo do jeito que aparece na prévia, `bannerHtml`), então ele nasce normalizado. Comparar
	 * o pedido já convertido contra a lista original era o que recusava justamente o `bannerHtml`
	 * como campo desconhecido.
	 */
	private static final Set<String> IMPORTABLE_SET = IMPORTABLE_FIELDS.stream()
		.map(field -> field.toLowerCase(Locale.ROOT))
		.collect(Collectors.toUnmodifiableSet());

	private final CorporateProfileRepository repository;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public CorporateProfileService(CorporateProfileRepository repository, UserProfileRepository profiles,
			Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.clock = clock;
	}

	/** Cria o perfil do usuário (1:1: um por conta). Idempotente: já existe, devolve o atual. */
	public CorporateProfile create(String userId, String username, String name) {
		if (repository.findByUserId(userId).isPresent()) {
			throw new ApiException(409, "CORPORATE_PROFILE_EXISTS",
				"Você já tem um perfil profissional. Edite-o em vez de criar outro.");
		}
		if (repository.existsByUsername(normalize(username))) {
			throw new ApiException(409, "USERNAME_TAKEN",
				"Esse usuário profissional já está em uso. Escolha outro.");
		}
		return repository.save(CorporateProfile.create(userId, username, name, clock.instant()));
	}

	public Optional<CorporateProfile> byUser(String userId) {
		return repository.findByUserId(userId);
	}

	public Optional<CorporateProfile> byUsername(String username) {
		return repository.findByUsername(normalize(username));
	}

	public CorporateProfile updateDetails(String userId, String headline, String bio, String avatar,
			String banner, String bannerHtml, String location, String industry, String externalLinks) {
		CorporateProfile current = byUserIdOrThrow(userId);
		return repository.save(current.withDetails(
			trimOrNull(headline, 200), trimOrNull(bio, 2000), trimOrNull(avatar, 500), trimOrNull(banner, 500),
			trimOrNull(bannerHtml, 20000), trimOrNull(location, 200), trimOrNull(industry, 200),
			com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrls(trimOrNull(externalLinks, 1000), "[,\\s]+", "links externos"),
			clock.instant()));
	}

	/**
	 * Prévia da importação: o que o perfil social oferece, já ajustado aos limites do perfil
	 * profissional. NADA é escrito aqui — é só a lista para a pessoa revisar.
	 */
	public ImportPreview importPreview(String userId) {
		UserProfile social = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil social não encontrado."));
		return new ImportPreview(
			fit(social.name(), 160), fit(social.headline(), 200), fit(social.bio(), 2000),
			fit(social.avatar(), 500), fit(social.banner(), 500), fitHtml(social.bannerHtml(), 20000),
			fit(social.location(), 200));
	}

	public record ImportPreview(String name, String headline, String bio, String avatar, String banner,
			String bannerHtml, String location) {
	}

	/**
	 * Traz do perfil social SOMENTE os campos escolhidos explicitamente (consentimento campo a
	 * campo). Lista vazia é recusada — nada é copiado em bloco nem por padrão; o que não foi
	 * escolhido continua como estava no perfil profissional.
	 */
	public CorporateProfile importFromSocial(String userId, List<String> fields) {
		if (fields == null || fields.isEmpty()) {
			throw new ApiException(400, "NO_FIELDS_SELECTED",
				"Escolha pelo menos um campo para trazer do perfil social.");
		}
		CorporateProfile current = byUserIdOrThrow(userId);
		UserProfile social = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil social não encontrado."));
		Set<String> selected = new java.util.HashSet<>();
		for (String field : fields) {
			String key = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
			if (!IMPORTABLE_SET.contains(key)) {
				throw new ApiException(400, "INVALID_IMPORT_FIELD",
					"Campo desconhecido para importação: " + key);
			}
			selected.add(key);
		}
		String headline = selected.contains("headline") ? fit(social.headline(), 200) : current.headline();
		String bio = selected.contains("bio") ? fit(social.bio(), 2000) : current.bio();
		String avatar = selected.contains("avatar") ? fit(social.avatar(), 500) : current.avatar();
		String banner = selected.contains("banner") ? fit(social.banner(), 500) : current.banner();
		String bannerHtml = selected.contains("bannerhtml") ? fitHtml(social.bannerHtml(), 20000) : current.bannerHtml();
		String location = selected.contains("location") ? fit(social.location(), 200) : current.location();
		CorporateProfile updated = current.withDetails(headline, bio, avatar, banner, bannerHtml, location,
			current.industry(), current.externalLinks(), clock.instant());
		if (selected.contains("name") && StringUtils.hasText(social.name())) {
			updated = updated.withName(social.name(), clock.instant());
		}
		return repository.save(updated);
	}

	/** Ajusta ao limite do campo profissional sem quebrar o texto (o preview mostra o resultado). */
	private String fit(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() <= max) {
			return trimmed;
		}
		return trimmed.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	/**
	 * Markup tem regra própria: cortar HTML no meio de uma tag transforma o resto da capa em
	 * texto visível (o navegador segue lendo o atributo até achar o fechamento). O corte cai no
	 * último `>` que cabe; se nem isso cabe, o campo fica vazio em vez de virar markup quebrado.
	 */
	static String fitHtml(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() <= max) {
			return trimmed;
		}
		int corte = trimmed.lastIndexOf('>', max - 1);
		return corte <= 0 ? null : trimmed.substring(0, corte + 1);
	}

	public CorporateProfile rename(String userId, String name) {
		CorporateProfile current = byUserIdOrThrow(userId);
		return repository.save(current.withName(name, clock.instant()));
	}

	/**
	 * Troca o nametag do perfil profissional: é o `@` E o fim da URL pública
	 * (`/profissional/{nametag}`).
	 *
	 * O nametag vive em namespace próprio (índice único no banco) e é independente do @ social —
	 * a mesma pessoa pode ser `aminadojava` no social e `melsantos` no profissional. Mandar o
	 * valor que já está lá não é erro nem gera escrita; o nametag de outra pessoa é 409.
	 */
	public CorporateProfile changeUsername(String userId, String username) {
		CorporateProfile current = byUserIdOrThrow(userId);
		String desired = normalize(username);
		if (desired.isEmpty()) {
			throw new ApiException(400, "INVALID_USERNAME",
				"Escolha o nametag do perfil profissional: é ele que aparece como @ e no fim da URL.");
		}
		if (desired.equals(current.username())) {
			return current;
		}
		if (repository.existsByUsername(desired)) {
			throw new ApiException(409, "USERNAME_TAKEN",
				"Esse usuário profissional já está em uso. Escolha outro.");
		}
		try {
			return repository.save(current.withUsername(desired, clock.instant()));
		}
		catch (IllegalArgumentException invalido) {
			// O formato é regra do domínio; a mensagem dela é o que a pessoa precisa ler.
			throw new ApiException(400, "INVALID_USERNAME", invalido.getMessage());
		}
	}

	/** Ativação exige nome e headline: perfil incompleto nunca aparece como pronto. */
	public CorporateProfile activate(String userId) {
		CorporateProfile current = byUserIdOrThrow(userId);
		if (!StringUtils.hasText(current.headline())) {
			throw new ApiException(409, "PROFILE_INCOMPLETE",
				"Preencha a headline do perfil profissional antes de ativá-lo.");
		}
		return repository.save(current.activate(clock.instant()));
	}

	public CorporateProfile deactivate(String userId) {
		return repository.save(byUserIdOrThrow(userId).disable(clock.instant()));
	}

	private CorporateProfile byUserIdOrThrow(String userId) {
		return repository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "NO_BUSINESS_PROFILE",
				"Você ainda não tem um perfil profissional. Crie um para usar os recursos de trabalho."));
	}

	private String normalize(String username) {
		return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
	}

	private String trimOrNull(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG", "Um dos campos passou do limite de " + max + " caracteres.");
		}
		return trimmed;
	}
}
