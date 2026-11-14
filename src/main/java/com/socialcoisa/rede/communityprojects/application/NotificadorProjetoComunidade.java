package com.socialcoisa.rede.communityprojects.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.communityprojects.application.port.out.CommunityProjectRepository;
import com.socialcoisa.rede.communityprojects.domain.CommunityProject;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.notifications.application.NotificationPreferenceService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.notifications.application.port.out.NotificationPreferenceRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.outbox.OutboxEventHandler;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Um lote por evento; avisos, continuação e recibo pertencem à transação do worker existente. */
@Component
class NotificadorProjetoComunidade implements OutboxEventHandler {

	private static final int TAMANHO_LOTE = 200;
	private final CommunityProjectRepository projetos;
	private final UserProfileRepository perfis;
	private final NotificationPreferenceRepository assinaturas;
	private final NotificationPreferenceService preferencias;
	private final NotificationService notifications;
	private final BlockedUsersReader bloqueios;
	private final SanctionGateReader sancoes;
	private final OutboxEventRepository outbox;
	private final ObjectMapper json;

	NotificadorProjetoComunidade(CommunityProjectRepository projetos, UserProfileRepository perfis,
			NotificationPreferenceRepository assinaturas, NotificationPreferenceService preferencias,
			NotificationService notifications, BlockedUsersReader bloqueios, SanctionGateReader sancoes,
			OutboxEventRepository outbox, ObjectMapper json) {
		this.projetos = projetos;
		this.perfis = perfis;
		this.assinaturas = assinaturas;
		this.preferencias = preferencias;
		this.notifications = notifications;
		this.bloqueios = bloqueios;
		this.sancoes = sancoes;
		this.outbox = outbox;
		this.json = json;
	}

	@Override
	public void handle(String eventType, String aggregateId, String payload) {
		if (!"CommunityProjectPublished".equals(eventType)) return;
		String cursor = cursor(payload);
		CommunityProject projeto = projetos.findById(aggregateId).filter(CommunityProject::isPublished).orElse(null);
		if (projeto == null) return;
		UserProfile autor = perfis.findById(projeto.userId()).filter(perfil -> !perfil.removed()).orElse(null);
		if (autor == null || sancoes.banned(autor.id())) return;
		List<String> lote = assinaturas.findCommunityProjectSubscriberIds(cursor, TAMANHO_LOTE);
		entregar(projeto, autor, lote);
		if (lote.size() == TAMANHO_LOTE) {
			outbox.append(eventType, aggregateId, json.writeValueAsString(Map.of("afterUserId", lote.getLast())));
		}
	}

	private void entregar(CommunityProject projeto, UserProfile autor, List<String> lote) {
		Set<String> ativos = destinatariosAtivos(lote);
		Set<String> banidos = sancoes.bannedAmong(lote);
		String href = "/projetos/" + autor.username() + "/" + projeto.slug();
		for (String destinatario : lote) {
			if (destinatario.equals(autor.id()) || !ativos.contains(destinatario) || banidos.contains(destinatario)) continue;
			if (!preferencias.of(destinatario).communityProjects() || bloqueios.blockedBetween(autor.id(), destinatario)) continue;
			notifications.create(destinatario, "NEW_COMMUNITY_PROJECT", autor.id(), "Novo projeto da comunidade",
				autor.name() + " publicou " + projeto.title() + ".", href, "Ver projeto");
		}
	}

	private Set<String> destinatariosAtivos(List<String> lote) {
		if (lote.isEmpty()) return Set.of();
		return perfis.identitiesOf(lote).stream()
			.filter(perfil -> !perfil.username().startsWith(UserProfile.REMOVED_USERNAME_PREFIX))
			.map(perfil -> perfil.id()).collect(Collectors.toSet());
	}

	private String cursor(String payload) {
		try {
			var raiz = json.readTree(payload);
			if (raiz == null || !raiz.isObject()) {
				throw new IllegalArgumentException("Entrega de projeto: esperado objeto JSON com afterUserId textual.");
			}
			var cursor = raiz.path("afterUserId");
			if (!cursor.isString() || cursor.asString().length() > 255) {
				throw new IllegalArgumentException("Entrega de projeto: afterUserId deve ser texto de até 255 caracteres.");
			}
			return cursor.asString();
		}
		catch (JacksonException invalido) {
			throw new IllegalArgumentException("Entrega de projeto: esperado objeto JSON com afterUserId textual.", invalido);
		}
	}
}
