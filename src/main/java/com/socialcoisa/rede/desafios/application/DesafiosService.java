package com.socialcoisa.rede.desafios.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.desafios.application.DesafiosViews.DesafioAuthor;
import com.socialcoisa.rede.desafios.application.DesafiosViews.DesafioView;
import com.socialcoisa.rede.desafios.application.DesafiosViews.ParticipacaoView;
import com.socialcoisa.rede.desafios.application.port.in.DesafiosModerationPort;
import com.socialcoisa.rede.desafios.application.port.out.DesafiosRepository;
import com.socialcoisa.rede.desafios.domain.Desafio;
import com.socialcoisa.rede.desafios.domain.DesafioStatus;
import com.socialcoisa.rede.desafios.domain.Participacao;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Regras de Desafios: um convite público com responsável nominal — quem convoca descreve o que
 * vale a pena fazer e quando termina, quem topa publica o que fez (texto, uma participação por
 * pessoa), quem convocou encerra escolhendo o que mais representou o desafio. Sem gamificação:
 * sem pontos, ranking ou prêmio — o encerramento é reconhecimento nominal dentro do próprio
 * desafio. Os gates de publicação (idade, sanção, ritmo), os bloqueios e as denúncias são os
 * mesmos do resto da plataforma — nada de sistema paralelo. Conteúdo vive só aqui, fora dos
 * feeds gerais, do perfil, da busca e dos contadores.
 */
@Service
public class DesafiosService implements DesafiosModerationPort {

	public static final int MAX_TITLE = 120;
	public static final int MAX_DESCRIPTION = 3000;
	public static final int MAX_PARTICIPACAO = 5000;
	/** Janela máxima do desafio: convite sem fim vira limbo; um mês é o teto. */
	public static final long MAX_DAYS = 31;
	public static final int MAX_PAGE_SIZE = 48;
	public static final int DEFAULT_PAGE_SIZE = 24;

	private final DesafiosRepository repository;
	private final UserProfileRepository profiles;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final UserRateLimiter userRateLimiter;
	private final BlockedUsersReader blockedUsers;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public DesafiosService(DesafiosRepository repository, UserProfileRepository profiles, AgeGate agePolicy,
			SanctionGate sanctionGate, UserRateLimiter userRateLimiter, BlockedUsersReader blockedUsers,
			CursorCodec cursorCodec, Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.userRateLimiter = userRateLimiter;
		this.blockedUsers = blockedUsers;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	// ---- Desafio: criar, encerrar, remover ----

	@Transactional
	public DesafioView publish(UserId authorId, String title, String description, Instant endsAt) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_POST);
		String titulo = normalizeTitle(title);
		String descricao = normalizeDescription(description);
		Instant fim = normalizeEndsAt(endsAt);
		Desafio desafio = repository.saveDesafio(Desafio.publish(
			"desafio_" + UUID.randomUUID().toString().replace("-", ""), authorId, titulo, descricao,
			clock.instant(), fim));
		return toDesafioView(List.of(desafio), authorId).get(0);
	}

	@Transactional
	public DesafioView close(UserId userId, String desafioId, String destaqueId) {
		Desafio desafio = activeDesafio(desafioId);
		if (!desafio.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só quem convocou encerra o desafio.");
		}
		String destaque = Optional.ofNullable(StringUtils.hasText(destaqueId) ? destaqueId : null)
			.filter(id -> repository.findParticipacaoById(id)
				.filter(p -> p.isActive() && p.desafioId().equals(desafioId))
				.isPresent())
			.orElseThrow(() -> new ApiException(400, "DESTAQUE_INVALID",
				"O reconhecimento precisa ser uma participação no ar deste desafio."));
		desafio.close(clock.instant(), Optional.of(destaque));
		repository.saveDesafio(desafio);
		return toDesafioView(List.of(desafio), userId).get(0);
	}

	@Transactional
	public void removeOwn(UserId userId, String desafioId) {
		Desafio desafio = activeDesafio(desafioId);
		if (!desafio.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só quem convocou remove o próprio desafio.");
		}
		desafio.remove(clock.instant());
		repository.saveDesafio(desafio);
		for (Participacao participacao : repository.findActiveParticipacoesByDesafio(desafioId)) {
			participacao.remove();
			repository.saveParticipacao(participacao);
		}
	}

	// ---- Participação ----

	@Transactional
	public ParticipacaoView participate(UserId authorId, String desafioId, String content) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_POST);
		Desafio desafio = activeDesafio(desafioId);
		if (!desafio.aceitaParticipacao(clock.instant())) {
			throw new ApiException(400, "DESAFIO_FECHADO", "Este desafio não recebe mais participação.");
		}
		if (desafio.authorId().equals(authorId)) {
			throw new ApiException(400, "OWN_CHALLENGE", "Quem convocou não participa do próprio desafio.");
		}
		String texto = normalizeParticipacao(content);
		// Uma participação por pessoa: republicar substitui a anterior (a chave é o par).
		repository.findParticipacaoDoAutor(desafioId, authorId.value()).ifPresent(anterior -> {
			anterior.remove();
			repository.saveParticipacao(anterior);
		});
		Participacao participacao = repository.saveParticipacao(Participacao.publish(
			"desafio_p_" + UUID.randomUUID().toString().replace("-", ""), desafioId, authorId, texto,
			clock.instant()));
		return toParticipacaoViews(List.of(participacao), authorId).get(0);
	}

	@Transactional
	public void removeOwnParticipacao(UserId userId, String desafioId, String participacaoId) {
		Participacao participacao = repository.findParticipacaoById(participacaoId)
			.filter(Participacao::isActive)
			.filter(p -> p.desafioId().equals(desafioId))
			.orElseThrow(() -> new ApiException(404, "PARTICIPACAO_NOT_FOUND", "Participação não encontrada."));
		boolean donaDoDesafio = repository.findDesafioById(desafioId)
			.map(d -> d.authorId().equals(userId))
			.orElse(false);
		if (!participacao.authorId().equals(userId) && !donaDoDesafio) {
			throw new ApiException(403, "NOT_AUTHORIZED",
				"Só o autor da participação ou quem convocou remove do desafio.");
		}
		participacao.remove();
		repository.saveParticipacao(participacao);
	}

	// ---- Torcida ----

	@Transactional
	public Map<String, Long> cheer(UserId userId, String participacaoId) {
		Participacao participacao = activeParticipacao(participacaoId);
		repository.cheer(participacao.id(), userId.value());
		return repository.countCheersByParticipacaoIds(List.of(participacao.id()));
	}

	@Transactional
	public void uncheer(UserId userId, String participacaoId) {
		activeParticipacao(participacaoId);
		repository.uncheer(participacaoId, userId.value());
	}

	// ---- Leitura ----

	@Transactional(readOnly = true)
	public DesafiosViews.DesafiosPage getFeed(UserId viewer, Optional<Instant> before, int limit) {
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<Desafio> desafios = repository.findFeedBefore(marca, tamanho);
		// Bloqueio esconde o desafio de quem foi bloqueado; o cursor sai do último BRUTO da
		// página, então a marca de tempo continua valendo para a página seguinte.
		Set<String> hidden = hiddenFor(viewer);
		List<DesafioView> items = toDesafioView(
			hidden.isEmpty() ? desafios
				: desafios.stream().filter(d -> !hidden.contains(d.authorId().value())).toList(),
			viewer);
		String nextCursor = desafios.size() == tamanho
			? cursorCodec.encode(desafios.get(desafios.size() - 1).createdAt())
			: null;
		return new DesafiosViews.DesafiosPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public DesafioView getDesafio(UserId viewer, String desafioId) {
		return toDesafioView(List.of(activeDesafio(desafioId)), viewer).get(0);
	}

	@Transactional(readOnly = true)
	public DesafiosViews.ParticipacoesPage getParticipacoes(UserId viewer, String desafioId) {
		activeDesafio(desafioId);
		List<Participacao> todas = repository.findActiveParticipacoesByDesafio(desafioId);
		Set<String> hidden = hiddenFor(viewer);
		if (!hidden.isEmpty()) {
			todas = todas.stream().filter(p -> !hidden.contains(p.authorId().value())).toList();
		}
		return new DesafiosViews.ParticipacoesPage(toParticipacaoViews(todas, viewer));
	}

	// ---- Moderação (port DesafiosModerationPort) ----

	@Override
	@Transactional
	public boolean removeDesafioAsModerator(String actorId, String desafioId, String reason) {
		return repository.findDesafioById(desafioId)
			.filter(d -> d.status() != DesafioStatus.REMOVED)
			.map(desafio -> {
				desafio.remove(clock.instant());
				repository.saveDesafio(desafio);
				for (Participacao participacao : repository.findActiveParticipacoesByDesafio(desafioId)) {
					participacao.remove();
					repository.saveParticipacao(participacao);
				}
				return true;
			})
			.orElse(false);
	}

	@Override
	@Transactional
	public boolean removeParticipacaoAsModerator(String actorId, String participacaoId, String reason) {
		return repository.findParticipacaoById(participacaoId)
			.filter(Participacao::isActive)
			.map(participacao -> {
				participacao.remove();
				repository.saveParticipacao(participacao);
				return true;
			})
			.orElse(false);
	}

	// ---- Internos ----

	private Desafio activeDesafio(String desafioId) {
		return repository.findDesafioById(desafioId)
			.filter(d -> d.status() != DesafioStatus.REMOVED)
			.orElseThrow(() -> new ApiException(404, "DESAFIO_NOT_FOUND", "Desafio não encontrado."));
	}

	private Participacao activeParticipacao(String participacaoId) {
		return repository.findParticipacaoById(participacaoId)
			.filter(Participacao::isActive)
			.orElseThrow(() -> new ApiException(404, "PARTICIPACAO_NOT_FOUND", "Participação não encontrada."));
	}

	private String normalizeTitle(String title) {
		String titulo = title == null ? "" : title.trim();
		if (!StringUtils.hasText(titulo)) {
			throw new ApiException(400, "TITLE_REQUIRED", "O desafio precisa de um título.");
		}
		if (titulo.length() > MAX_TITLE) {
			throw new ApiException(400, "TITLE_TOO_LONG",
				"O título aceita no máximo " + MAX_TITLE + " caracteres.");
		}
		return titulo;
	}

	private String normalizeDescription(String description) {
		String descricao = description == null ? "" : description.trim();
		if (!StringUtils.hasText(descricao)) {
			throw new ApiException(400, "DESCRIPTION_REQUIRED", "Descreva o desafio — o convite é o texto.");
		}
		if (descricao.length() > MAX_DESCRIPTION) {
			throw new ApiException(400, "DESCRIPTION_TOO_LONG",
				"A descrição aceita no máximo " + MAX_DESCRIPTION + " caracteres.");
		}
		return descricao;
	}

	private String normalizeParticipacao(String content) {
		String texto = content == null ? "" : content.trim();
		if (!StringUtils.hasText(texto)) {
			throw new ApiException(400, "CONTENT_REQUIRED", "Conte o que você fez — a participação é o texto.");
		}
		if (texto.length() > MAX_PARTICIPACAO) {
			throw new ApiException(400, "CONTENT_TOO_LONG",
				"A participação aceita no máximo " + MAX_PARTICIPACAO + " caracteres.");
		}
		return texto;
	}

	private Instant normalizeEndsAt(Instant endsAt) {
		if (endsAt == null) {
			return null;
		}
		Instant agora = clock.instant();
		if (!endsAt.isAfter(agora)) {
			throw new ApiException(400, "ENDS_AT_PAST", "A janela do desafio precisa terminar no futuro.");
		}
		if (endsAt.isAfter(agora.plus(MAX_DAYS, ChronoUnit.DAYS))) {
			throw new ApiException(400, "ENDS_AT_TOO_FAR",
				"O desafio dura no máximo " + MAX_DAYS + " dias.");
		}
		return endsAt.truncatedTo(ChronoUnit.MINUTES);
	}

	private Set<String> hiddenFor(UserId viewer) {
		return blockedUsers.hiddenIdsFor(viewer == null ? null : viewer.value());
	}

	private List<DesafioView> toDesafioView(List<Desafio> desafios, UserId viewer) {
		List<Desafio> visiveis = desafios.stream()
			.filter(d -> d.status() != DesafioStatus.REMOVED)
			.toList();
		if (visiveis.isEmpty()) {
			return List.of();
		}
		Map<String, UserProfile> autores = profiles.findAllByIds(
			visiveis.stream().map(d -> d.authorId().value()).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		return visiveis.stream().map(desafio -> new DesafioView(desafio.id(), desafio.title(), desafio.description(),
			toAuthor(desafio.authorId().value(), autores.get(desafio.authorId().value())),
			desafio.createdAt(), desafio.endsAt(), desafio.status().name(), desafio.destaqueId(),
			repository.countActiveParticipacoes(desafio.id()),
			viewer != null && desafio.authorId().equals(viewer))).toList();
	}

	private List<ParticipacaoView> toParticipacaoViews(List<Participacao> participacoes, UserId viewer) {
		if (participacoes.isEmpty()) {
			return List.of();
		}
		Map<String, UserProfile> autores = profiles.findAllByIds(
			participacoes.stream().map(p -> p.authorId().value()).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		List<String> ids = participacoes.stream().map(Participacao::id).toList();
		Map<String, Long> torcidas = repository.countCheersByParticipacaoIds(ids);
		Set<String> minhas = viewer == null ? Set.of() : repository.findUserCheers(viewer.value(), Set.copyOf(ids));
		return participacoes.stream().map(participacao -> new ParticipacaoView(participacao.id(),
			participacao.desafioId(),
			toAuthor(participacao.authorId().value(), autores.get(participacao.authorId().value())),
			participacao.content(), participacao.createdAt(),
			torcidas.getOrDefault(participacao.id(), 0L), minhas.contains(participacao.id()),
			viewer != null && participacao.authorId().equals(viewer))).toList();
	}

	private DesafioAuthor toAuthor(String id, UserProfile profile) {
		if (profile == null) {
			return new DesafioAuthor(id, null, null, null, null, null);
		}
		return new DesafioAuthor(profile.id(), profile.name(), profile.username(), profile.avatar(),
			profile.avatarFrame(), com.socialcoisa.rede.profile.application.NameStyleView.from(profile));
	}

}
