package com.socialcoisa.rede.shared.web;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperationalController {

	@GetMapping("/health")
	Map<String, String> health() {
		return Map.of("status", "UP");
	}

	@GetMapping("/openapi.json")
	Map<String, Object> openapi() {
		Map<String, Object> paths = new LinkedHashMap<>();
		paths.put("/api/auth/signup", Map.of("post", Map.of("summary", "Cadastra usuario e cria sessao")));
		paths.put("/api/auth/login", Map.of("post", Map.of("summary", "Autentica usuario e cria sessao")));
		paths.put("/api/auth/logout", Map.of("post", Map.of("summary", "Revoga sessao atual")));
		paths.put("/api/auth/me", Map.of("get", Map.of("summary", "Retorna usuario autenticado")));
		paths.put("/api/auth/oauth/{provider}/start", Map.of("get", Map.of("summary", "Inicia fluxo OAuth")));
		paths.put("/api/auth/oauth/{provider}/callback", Map.of("get", Map.of("summary", "Recebe callback OAuth")));
		paths.put("/api/auth/password/forgot", Map.of("post", Map.of("summary", "Solicita reset de senha")));
		paths.put("/api/auth/password/reset", Map.of("post", Map.of("summary", "Confirma reset de senha")));
		paths.put("/api/me", Map.of("get", Map.of("summary", "Retorna perfil do usuario autenticado")));
		paths.put("/api/users/{username}", Map.of("get", Map.of("summary", "Retorna perfil publico por username")));
		paths.put("/api/me/profile", Map.of("patch", Map.of("summary", "Atualiza perfil do usuario autenticado")));
		paths.put("/api/search", Map.of("get", Map.of("summary", "Busca global")));
		paths.put("/api/media/uploads", Map.of("post", Map.of("summary", "Cria contrato de upload de midia")));
		paths.put("/api/media/{mediaId}", Map.of(
			"get", Map.of("summary", "Retorna metadados de midia"),
			"delete", Map.of("summary", "Solicita delecao de midia")));
		paths.put("/api/network/users", Map.of("get", Map.of("summary", "Lista usuarios da rede")));
		paths.put("/api/network/suggestions", Map.of("get", Map.of("summary", "Lista sugestoes de conexao")));
		paths.put("/api/network/summary", Map.of("get", Map.of("summary", "Resumo da rede do usuario")));
		paths.put("/api/network/requests", Map.of("get", Map.of("summary", "Lista solicitacoes de conexao")));
		paths.put("/api/network/connections/{userId}", Map.of(
			"post", Map.of("summary", "Solicita conexao"),
			"delete", Map.of("summary", "Remove conexao")));
		paths.put("/api/network/connections/{userId}/accept", Map.of("put", Map.of("summary", "Aceita conexao")));
		paths.put("/api/feed/posts", Map.of("post", Map.of("summary", "Publica post no feed")));
		paths.put("/api/feed", Map.of("get", Map.of("summary", "Lista feed do usuario autenticado")));
		paths.put("/api/users/{username}/posts", Map.of("get", Map.of("summary", "Lista posts publicos do perfil")));
		paths.put("/api/feed/posts/{postId}", Map.of("delete", Map.of("summary", "Exclui post do autor")));
		paths.put("/api/feed/posts/bulk-delete", Map.of("post", Map.of("summary", "Exclui posts do autor em lote")));
		paths.put("/api/feed/posts/{postId}/comments", Map.of(
			"post", Map.of("summary", "Comenta ou responde em um post"),
			"get", Map.of("summary", "Lista comentarios de um post")));
		paths.put("/api/feed/posts/{postId}/reactions", Map.of(
			"post", Map.of("summary", "Registra reacao em um post"),
			"delete", Map.of("summary", "Remove reacao do usuario em um post")));
		paths.put("/api/feed/posts/{postId}/saves", Map.of(
			"post", Map.of("summary", "Salva post para o usuario"),
			"delete", Map.of("summary", "Remove post salvo do usuario")));
		paths.put("/api/feed/posts/{postId}/shares", Map.of("post", Map.of("summary", "Compartilha post")));
		paths.put("/api/communities", Map.of(
			"get", Map.of("summary", "Lista comunidades"),
			"post", Map.of("summary", "Cria comunidade")));
		paths.put("/api/communities/{communityId}", Map.of(
			"get", Map.of("summary", "Detalha comunidade"),
			"patch", Map.of("summary", "Atualiza comunidade")));
		paths.put("/api/communities/{communityId}/members", Map.of(
			"post", Map.of("summary", "Entra na comunidade"),
			"delete", Map.of("summary", "Sai da comunidade")));
		paths.put("/api/jobs", Map.of("get", Map.of("summary", "Lista vagas")));
		paths.put("/api/jobs/{jobId}", Map.of("get", Map.of("summary", "Detalha vaga")));
		paths.put("/api/jobs/{jobId}/applications", Map.of("post", Map.of("summary", "Candidata usuario a vaga")));
		paths.put("/api/jobs/{jobId}/saves", Map.of(
			"post", Map.of("summary", "Salva vaga"),
			"delete", Map.of("summary", "Remove vaga salva")));
		paths.put("/api/events", Map.of("get", Map.of("summary", "Lista eventos")));
		paths.put("/api/events/{eventId}", Map.of("get", Map.of("summary", "Detalha evento")));
		paths.put("/api/events/{eventId}/attendees", Map.of(
			"post", Map.of("summary", "Confirma presenca no evento"),
			"delete", Map.of("summary", "Cancela presenca no evento")));
		paths.put("/api/mentors", Map.of("get", Map.of("summary", "Lista mentores")));
		paths.put("/api/mentors/{mentorId}", Map.of("get", Map.of("summary", "Detalha mentor")));
		paths.put("/api/mentors/{mentorId}/slots", Map.of("get", Map.of("summary", "Lista horarios do mentor")));
		paths.put("/api/mentors/{mentorId}/sessions", Map.of("post", Map.of("summary", "Agenda sessao de mentoria")));
		paths.put("/api/conversations", Map.of(
			"get", Map.of("summary", "Lista conversas"),
			"post", Map.of("summary", "Cria conversa")));
		paths.put("/api/conversations/search", Map.of("get", Map.of("summary", "Busca conversas")));
		paths.put("/api/conversations/{conversationId}", Map.of("get", Map.of("summary", "Detalha conversa")));
		paths.put("/api/conversations/{conversationId}/messages", Map.of(
			"get", Map.of("summary", "Lista mensagens"),
			"post", Map.of("summary", "Envia mensagem")));
		paths.put("/api/conversations/{conversationId}/read", Map.of("post", Map.of("summary", "Marca conversa como lida")));
		paths.put("/api/notifications", Map.of("get", Map.of("summary", "Lista notificacoes")));
		paths.put("/api/notifications/{notificationId}/read", Map.of("post", Map.of("summary", "Marca notificacao como lida")));
		paths.put("/api/notifications/read-all", Map.of("post", Map.of("summary", "Marca todas notificacoes como lidas")));
		return Map.of(
			"openapi", "3.1.0",
			"info", Map.of("title", "Rede API", "version", "0.1.0"),
			"paths", paths,
			"tags", List.of(Map.of("name", "identity"), Map.of("name", "profile"), Map.of("name", "feed"),
				Map.of("name", "network"), Map.of("name", "media"), Map.of("name", "communities"),
				Map.of("name", "jobs"), Map.of("name", "events"), Map.of("name", "mentoring"),
				Map.of("name", "messaging"), Map.of("name", "notifications"), Map.of("name", "search"),
				Map.of("name", "operations")));
	}
}
