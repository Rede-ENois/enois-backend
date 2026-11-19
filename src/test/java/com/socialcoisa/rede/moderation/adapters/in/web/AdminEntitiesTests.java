package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Gerenciamento de comunidades e grupos pela ADMINISTRAÇÃO da plataforma.
 *
 * <p>O que este teste tranca:</p>
 * <ul>
 * <li>admin exclui comunidade e fecha grupo de quem for, sem ser dono, sem votação;</li>
 * <li>dono, membro comum e moderador aprovado NÃO usam a superfície administrativa;</li>
 * <li>a lista completa de membros (com busca e páginas) só abre para admin, inclusive para quem
 *     não participa da comunidade/grupo;</li>
 * <li>admin tira membro — inclusive dono — e o grupo não fica órfão;</li>
 * <li>toda decisão deixa auditoria com o admin responsável e o motivo.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminEntitiesTests {

	private static final String REASON = "Decisão da administração da plataforma, com indícios de fraude.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.moderation.moderator-min-reputation", () -> "-1");
		registry.add("rede.moderation.automation.enabled", () -> "false");
		registry.add("MODERATION_AUTOMATION_ENABLED", () -> "false");
		// O mesmo DELETE repetido neste teste prova a idempotência pela chave, não a janela de 3s.
		registry.add("rede.security.duplicate-post-enabled", () -> "false");
	}

	// ------------------------------------------------------------------ comunidade

	@Test
	void adminExcluiComunidadeSemSerDonoEMembranaSomem() throws Exception {
		var admin = admin("aent_com_admin");
		var dono = auth().authenticate("aent_com_dono");
		var membro = auth().authenticate("aent_com_membro");
		String communityId = createCommunity(dono, "Comunidade Alvo", "aent-com-1");
		joinCommunity(membro, communityId, "aent-com-join-1");

		// Ficha administrativa: o admin vê dono e tamanho antes de decidir.
		mockMvc.perform(get("/admin/moderation/communities/" + communityId)
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(communityId))
			.andExpect(jsonPath("$.members").value(2));

		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-com-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("DELETED"))
			.andExpect(jsonPath("$.adminWasMember").value(false));

		// Repetir a MESMA chave devolve a mesma resposta sem segunda decisão.
		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-com-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk());

		// Integridade: comunidade e vínculos fora; a página dela responde 404.
		assertThat(count("select count(*) from communities where id = ?", communityId)).isZero();
		assertThat(count("select count(*) from community_members where community_id = ?", communityId)).isZero();
		mockMvc.perform(get("/communities/" + communityId)).andExpect(status().isNotFound());

		assertThat(one("select actor_id from audit_logs where action = 'ADMIN_COMMUNITY_DELETED' "
			+ "and target_id = ? order by created_at desc limit 1", communityId)).isEqualTo(admin.userId());
		assertThat(one("select metadata from audit_logs where action = 'ADMIN_COMMUNITY_DELETED' "
			+ "and target_id = ? order by created_at desc limit 1", communityId)).contains(REASON);
	}

	/**
	 * Exclusão administrativa de conta: a pessoa não sabe a senha, e o admin decide sem ela. A conta
	 * sai pelo MESMO caminho da auto-exclusão (perfil anonimizado, sessões revogadas), o motivo fica
	 * na auditoria e ninguém apaga a própria conta nem a de outro administrador.
	 */
	@Test
	void exclusaoAdministrativaDeContaAnonimizaERevogaSemSenha() throws Exception {
		var admin = admin("aent_del_admin");
		var alvo = auth().authenticate("aent_del_alvo");
		String username = one("select username from users where id = ?", alvo.userId());

		mockMvc.perform(post("/admin/moderation/users/" + username + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("DELETED"));

		assertThat(one("select username from users where id = ?", alvo.userId()))
			.as("perfil anonimizado")
			.startsWith("removed-");
		assertThat(count("select count(*) from auth_sessions where user_id = ? and revoked_at is null",
			alvo.userId())).as("sessões do alvo revogadas").isZero();
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_ACCOUNT_DELETED' "
			+ "and target_id = ?", alvo.userId())).isEqualTo(1);

		// Sem motivo não há decisão: a recusa vem antes de qualquer escrita.
		var outra = auth().authenticate("aent_del_outra");
		String outraUsername = one("select username from users where id = ?", outra.userId());
		mockMvc.perform(post("/admin/moderation/users/" + outraUsername + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-del-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest());
		assertThat(count("select count(*) from users where id = ? and username = ?",
			outra.userId(), outraUsername)).as("sem motivo, nada muda").isEqualTo(1);

		// A própria conta e a de outro administrador ficam de fora.
		String adminUsername = one("select username from users where id = ?", admin.userId());
		mockMvc.perform(post("/admin/moderation/users/" + adminUsername + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-del-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isBadRequest());
		var outroAdmin = admin("aent_del_admin2");
		String outroAdminUsername = one("select username from users where id = ?", outroAdmin.userId());
		mockMvc.perform(post("/admin/moderation/users/" + outroAdminUsername + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-del-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isConflict());
		assertThat(one("select username from users where id = ?", outroAdmin.userId()))
			.as("conta admin intacta")
			.isEqualTo(outroAdminUsername);
	}

	/**
	 * A exclusão administrativa não pode deixar vínculo para trás: canal, publicação de canal, fixado e
	 * favorito não têm chave estrangeira para `communities` (V33, V97, V139), então só a limpeza
	 * explícita dentro da transação os leva embora. Antes desta cobertura, excluir deixava canal e
	 * fixado órfãos no banco.
	 */
	@Test
	void exclusaoAdministrativaDeComunidadeNaoDeixaVinculoOrfao() throws Exception {
		var admin = admin("aent_orf_admin");
		var dono = auth().authenticate("aent_orf_dono");
		var fa = auth().authenticate("aent_orf_fa");
		String communityId = createCommunity(dono, "Comunidade com canal", "aent-orf-1");
		joinCommunity(fa, communityId, "aent-orf-join-1");
		MvcResult channel = mockMvc.perform(post("/communities/" + communityId + "/channels")
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Geral"))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String channelId = objectMapper.readTree(channel.getResponse().getContentAsString()).get("id").asText();
		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "aent-orf-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "INSIGHT",
					"content", "Publicação que fica órfã se o canal não sair junto com a comunidade.",
					"tags", List.of(), "mediaIds", List.of(), "communityId", communityId,
					"channelId", channelId))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();
		jdbc.update("insert into community_pinned_posts (community_id, post_id, pinned_by, pinned_at) "
			+ "values (?, ?, ?, now())", communityId, postId, dono.userId());
		jdbc.update("insert into favorite_communities (id, user_id, community_id, created_at) "
			+ "values (?, ?, ?, now())", "fav_orf_" + communityId, fa.userId(), communityId);

		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-orf-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk());

		assertThat(count("select count(*) from community_channels where community_id = ?", communityId)).isZero();
		assertThat(count("select count(*) from community_pinned_posts where community_id = ?", communityId)).isZero();
		assertThat(count("select count(*) from favorite_communities where community_id = ?", communityId)).isZero();
		assertThat(count("select count(*) from posts where community_id = ?", communityId)).isZero();
		assertThat(count("select count(*) from posts where id = ?", postId)).isZero();
		assertThat(count("select count(*) from community_members where community_id = ?", communityId)).isZero();
	}

	@Test
	void exclusaoAdministrativaDeComunidadeRecusaQuemNaoEAdmin() throws Exception {
		var dono = auth().authenticate("aent_neg_dono");
		var membro = auth().authenticate("aent_neg_membro");
		var moderador = moderator("aent_neg_mod");
		String communityId = createCommunity(dono, "Comunidade Intocada", "aent-neg-1");
		joinCommunity(membro, communityId, "aent-neg-join-1");

		// Dono: continua com o caminho dele (DELETE /communities/{id}), não com o administrativo.
		for (var session : List.of(dono, membro, moderador)) {
			mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
					.header("X-Session-Token", session.token())
					.header("Idempotency-Key", "aent-neg-del-" + session.userId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
				.andExpect(status().isForbidden());
		}
		// Sem sessão: 401, não 403 — a identidade é exigida antes do papel.
		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isUnauthorized());

		assertThat(count("select count(*) from communities where id = ?", communityId)).isEqualTo(1);
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_COMMUNITY_DELETED' "
			+ "and target_id = ?", communityId)).isZero();
	}

	@Test
	void exclusaoAdministrativaDeComunidadeExigeMotivo() throws Exception {
		var admin = admin("aent_why_admin");
		var dono = auth().authenticate("aent_why_dono");
		String communityId = createCommunity(dono, "Comunidade Com Motivo", "aent-why-1");

		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-why-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
		assertThat(count("select count(*) from communities where id = ?", communityId)).isEqualTo(1);

		// Id que não existe é 404, e não uma escrita sobre outra linha.
		mockMvc.perform(post("/admin/moderation/communities/nao-existe/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-why-del-404")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------------ grupo

	@Test
	void adminExcluiGrupoDeTerceirosSemQuorumSemDeixarOrfao() throws Exception {
		var admin = admin("aent_grp_admin");
		var dono = auth().authenticate("aent_grp_dono");
		var membro = auth().authenticate("aent_grp_membro");
		String groupId = createGroup(dono, "Grupo Alvo", "aent-grp-1");
		joinGroup(membro, groupId, "aent-grp-join-1");
		String topicId = topic(dono, groupId, "Geral", "aent-grp-topic-1");
		String postId = publishInGroup(dono, groupId, topicId, "conteudo do grupo", "aent-grp-post-1");
		react(dono, groupId, postId, "aent-grp-react-1");
		comment(dono, groupId, postId, "comentario do grupo", "aent-grp-comment-1");

		mockMvc.perform(get("/admin/moderation/groups/" + groupId).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ownerId").value(dono.userId()))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.members").value(2));

		mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-grp-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("DELETED"))
			.andExpect(jsonPath("$.name").value("Grupo Alvo"))
			.andExpect(jsonPath("$.removedPosts").value(1))
			.andExpect(jsonPath("$.adminWasMember").value(false));

		// A MESMA chave devolve a mesma resposta; a linha já não existe e nada é decidido de novo.
		mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/delete")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-grp-del-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk());

		// Integridade: grupo, membros, tópico, publicação, comentário e reação saem juntos.
		assertThat(count("select count(*) from groups where id = ?", groupId)).isZero();
		assertThat(count("select count(*) from group_members where group_id = ?", groupId)).isZero();
		assertThat(count("select count(*) from group_topics where group_id = ?", groupId)).isZero();
		assertThat(count("select count(*) from group_posts where group_id = ?", groupId)).isZero();
		assertThat(count("select count(*) from group_post_comments where group_id = ?", groupId)).isZero();
		assertThat(count("select count(*) from group_post_reactions where post_id = ?", postId)).isZero();
		mockMvc.perform(get("/groups/" + groupId).header("X-Session-Token", dono.token()))
			.andExpect(status().isNotFound());

		assertThat(one("select actor_id from audit_logs where action = 'ADMIN_GROUP_DELETED' "
			+ "and target_id = ? order by created_at desc limit 1", groupId)).isEqualTo(admin.userId());
		assertThat(one("select metadata from audit_logs where action = 'ADMIN_GROUP_DELETED' "
			+ "and target_id = ? order by created_at desc limit 1", groupId)).contains(REASON);
	}

	@Test
	void adminEncerraGrupoPreservandoConteudo() throws Exception {
		var admin = admin("aent_close_admin");
		var dono = auth().authenticate("aent_close_dono");
		String groupId = createGroup(dono, "Grupo Encerrado", "aent-close-1");
		String topicId = topic(dono, groupId, "Geral", "aent-close-topic");
		String postId = publishInGroup(dono, groupId, topicId, "historico do grupo", "aent-close-post");
		// A faixa que importa: o post CONTINUA no banco (o encerramento não apaga nada).
		assertThat(count("select count(*) from group_posts where id = ?", postId)).isEqualTo(1);

		mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/close")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-close-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("CLOSED"));

		mockMvc.perform(get("/groups/" + groupId).header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.status").value("CLOSED"));
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_GROUP_CLOSED' "
			+ "and target_id = ?", groupId)).isEqualTo(1);
		assertThat(one("select metadata from audit_logs where action = 'ADMIN_GROUP_CLOSED' "
			+ "and target_id = ?", groupId)).contains(REASON);
	}

	@Test
	void exclusaoAdministrativaDeGrupoRecusaDonoMembroEUsuarioComum() throws Exception {
		var dono = auth().authenticate("aent_gn_dono");
		var membro = auth().authenticate("aent_gn_membro");
		String groupId = createGroup(dono, "Grupo Intocado", "aent-gn-1");
		joinGroup(membro, groupId, "aent-gn-join-1");

		for (var session : List.of(dono, membro)) {
			mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/delete")
					.header("X-Session-Token", session.token())
					.header("Idempotency-Key", "aent-gn-del-" + session.userId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
				.andExpect(status().isForbidden());
		}
		assertThat(one("select status from groups where id = ?", groupId)).isEqualTo("ACTIVE");
	}

	// ---------------------------------------------------------------------- membros

	@Test
	void adminVeListaCompletaDeMembrosComBuscaEPaginas() throws Exception {
		var admin = admin("aent_list_admin");
		var dono = auth().authenticate("aent_list_dono");
		String communityId = createCommunity(dono, "Comunidade Grande", "aent-list-com");
		String groupId = createGroup(dono, "Grupo Grande", "aent-list-grp");
		for (int i = 0; i < 3; i++) {
			var pessoa = auth().authenticate("aent_list_pessoa" + i);
			joinCommunity(pessoa, communityId, "aent-list-com-join-" + i);
			joinGroup(pessoa, groupId, "aent-list-grp-join-" + i);
		}

		mockMvc.perform(get("/admin/moderation/communities/" + communityId + "/members?size=2&page=0")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.pages").value(2))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].username").isNotEmpty())
			.andExpect(jsonPath("$.items[0].joinedAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].role").isNotEmpty());

		mockMvc.perform(get("/admin/moderation/communities/" + communityId + "/members?size=2&page=1")
				.header("X-Session-Token", admin.token()))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.page").value(1));

		// Busca por @: só a pessoa procurada volta, e o total reflete a busca.
		mockMvc.perform(get("/admin/moderation/communities/" + communityId + "/members?q=pessoa1")
				.header("X-Session-Token", admin.token()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].username").value("aent-list-pessoa1"));

		mockMvc.perform(get("/admin/moderation/groups/" + groupId + "/members?size=2")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items.length()").value(2));

		// O admin não participa de nenhuma das duas e ainda assim enxerga tudo.
		assertThat(count("select count(*) from community_members where community_id = ? and user_id = ?",
			communityId, admin.userId())).isZero();
	}

	@Test
	void listaCompletaDeMembrosEExclusivaDoAdmin() throws Exception {
		var dono = auth().authenticate("aent_priv_dono");
		var membro = auth().authenticate("aent_priv_membro");
		var moderador = moderator("aent_priv_mod");
		String communityId = createCommunity(dono, "Comunidade Privada", "aent-priv-com");
		String groupId = createGroup(dono, "Grupo Privado", "aent-priv-grp");
		joinGroup(membro, groupId, "aent-priv-grp-join");

		for (String path : List.of("/admin/moderation/communities/" + communityId + "/members",
				"/admin/moderation/groups/" + groupId + "/members")) {
			for (var session : List.of(dono, membro, moderador)) {
				mockMvc.perform(get(path).header("X-Session-Token", session.token()))
					.andExpect(status().isForbidden());
			}
			mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		}

		// Mesmo sabendo o id, o moderador aprovado não entra na ficha nem na lista.
		mockMvc.perform(get("/admin/moderation/groups/" + groupId).header("X-Session-Token", moderador.token()))
			.andExpect(status().isForbidden());
		// Id inexistente para o admin é 404 (não vaza lista vazia como se existisse).
		mockMvc.perform(get("/admin/moderation/groups/nao-existe/members")
				.header("X-Session-Token", admin("aent_priv_admin").token()))
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------- vínculos

	@Test
	void adminRemoveMembroDeComunidadeEGrupoInclusiveODono() throws Exception {
		var admin = admin("aent_rm_admin");
		var dono = auth().authenticate("aent_rm_dono");
		var membro = auth().authenticate("aent_rm_membro");
		String communityId = createCommunity(dono, "Comunidade Remocao", "aent-rm-com");
		joinCommunity(membro, communityId, "aent-rm-com-join");
		String groupId = createGroup(dono, "Grupo Remocao", "aent-rm-grp");
		joinGroup(membro, groupId, "aent-rm-grp-join");

		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/members/" + membro.userId() + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-rm-com-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("REMOVED"));
		assertThat(count("select count(*) from community_members where community_id = ? and user_id = ?",
			communityId, membro.userId())).isZero();

		// Remoção de quem não é membro é 404; a do dono é aceita na comunidade.
		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/members/" + membro.userId() + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-rm-com-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/admin/moderation/communities/" + communityId + "/members/" + dono.userId() + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-rm-com-owner")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk());

		// No grupo, tirar o dono passa a propriedade para quem ficou: o grupo não fica órfão.
		mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/members/" + dono.userId() + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "aent-rm-grp-owner")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk());
		assertThat(one("select owner_id from groups where id = ?", groupId)).isEqualTo(membro.userId());
		assertThat(one("select role from group_members where group_id = ? and user_id = ?", groupId,
			membro.userId())).isEqualTo("OWNER");
		assertThat(count("select count(*) from group_members where group_id = ? and user_id = ?", groupId,
			dono.userId())).isZero();

		// Dono e membro comum não removem pela via administrativa.
		mockMvc.perform(post("/admin/moderation/groups/" + groupId + "/members/" + membro.userId() + "/remove")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "aent-rm-grp-neg")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());

		// Um por remoção aceita (membro e dono); a tentativa de remover quem já saíra é 404 e não audita.
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_COMMUNITY_MEMBER_REMOVED' "
			+ "and target_id = ?", communityId)).isEqualTo(2);
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_GROUP_MEMBER_REMOVED' "
			+ "and target_id = ?", groupId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------------ helpers

	private TestAuthHelper auth() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private TestAuthHelper.AuthSession admin(String name) throws Exception {
		var session = auth().authenticate(name);
		TestDatabase.setAdmin(session.userId());
		return session;
	}

	/** Moderador aprovado de verdade: candidatura + aprovação por um administrador. */
	private TestAuthHelper.AuthSession moderator(String name) throws Exception {
		var session = auth().authenticate(name);
		var boss = admin(name + "_boss");
		if (count("select count(*) from moderator_applications where user_id = ? and status in ('PENDING','APPROVED')",
			session.userId()) == 0) {
			mockMvc.perform(post("/moderation/applications")
					.header("X-Session-Token", session.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"motivation", "Quero ajudar a manter a comunidade segura e acolhedora para todas as pessoas.",
						"experience", "Já moderei comunidades.",
						"availability", "Todas as noites."))))
				.andExpect(status().is2xxSuccessful());
		}
		List<Map<String, Object>> pending = jdbc.queryForList(
			"select id from moderator_applications where user_id = ? and status = 'PENDING'", session.userId());
		for (Map<String, Object> row : pending) {
			mockMvc.perform(post("/admin/moderator-applications/" + row.get("id") + "/approve")
					.header("X-Session-Token", boss.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("note", "Aprovado no teste."))))
				.andExpect(status().isOk());
		}
		return session;
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name,
					"description", "Comunidade criada para o teste administrativo.",
					"topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void joinCommunity(TestAuthHelper.AuthSession user, String communityId, String key) throws Exception {
		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void joinGroup(TestAuthHelper.AuthSession user, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String topic(TestAuthHelper.AuthSession owner, String groupId, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publishInGroup(TestAuthHelper.AuthSession author, String groupId, String topicId, String content,
			String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicId", topicId, "content", content,
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void react(TestAuthHelper.AuthSession user, String groupId, String postId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/posts/" + postId + "/reactions")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "LIKE"))))
			.andExpect(status().is2xxSuccessful());
	}

	private void comment(TestAuthHelper.AuthSession user, String groupId, String postId, String content, String key)
			throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().is2xxSuccessful());
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private String one(String sql, Object... args) {
		List<String> rows = jdbc.queryForList(sql, String.class, args);
		return rows.isEmpty() ? null : rows.getFirst();
	}
}