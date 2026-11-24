package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.groups.domain.GroupCapability;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupPermissions;
import com.socialcoisa.rede.groups.domain.GroupRole;

/**
 * A tabela de autorização do grupo, papel por papel.
 *
 * O que este teste tranca:
 *
 * - o dono manda no grupo, mas não na plataforma;
 * - o administrador administra o grupo e NÃO nomeia administradores nem encerra o grupo;
 * - o moderador cuida do conteúdo e não da configuração;
 * - o membro participa e não modera;
 * - o visitante (papel nulo) só alcança a apresentação pública e denunciá-la;
 * - as quatro proibições do plano valem para TODO MUNDO, inclusive o dono;
 * - o grupo nunca fica sem uma autoridade válida (o último dono não sai).
 *
 * É o que impede a interface de virar a autoridade: cada endpoint pergunta por uma capacidade
 * daqui, então "escondi o botão" não é o que protege o grupo.
 */
@Tag("critico")
class GroupPermissionsTests {

	private static final List<GroupCapability> DO_GRUPO = List.of(
		GroupCapability.VIEW_PRIVATE_CONTENT,
		GroupCapability.PUBLISH,
		GroupCapability.INTERACT,
		GroupCapability.REPORT_CONTENT,
		GroupCapability.REPORT_GROUP,
		GroupCapability.MODERATE_CONTENT,
		GroupCapability.PIN_CONTENT,
		GroupCapability.MANAGE_TOPICS,
		GroupCapability.MANAGE_MEMBERS,
		GroupCapability.VIEW_JOIN_REQUESTS,
		GroupCapability.DECIDE_JOIN_REQUESTS,
		GroupCapability.EDIT_GROUP,
		GroupCapability.CONFIGURE_JOIN,
		GroupCapability.MANAGE_ROLES,
		GroupCapability.TRANSFER_OWNERSHIP,
		GroupCapability.CLOSE_GROUP);

	private static final List<GroupCapability> PROIBIDAS = List.of(
		GroupCapability.DELETE_REPORT_EVIDENCE,
		GroupCapability.DISABLE_AUTOMOD,
		GroupCapability.CHANGE_AUDIT_RECORDS,
		GroupCapability.GRANT_GLOBAL_PRIVILEGES);

	@Test
	void oDonoPodeTudoQueEDoGrupo() {
		for (GroupCapability capability : DO_GRUPO) {
			assertThat(GroupPermissions.can(GroupRole.OWNER, capability))
				.as("dono x %s", capability)
				.isTrue();
		}
	}

	@Test
	void oAdministradorAdministraOGrupoMasNaoANomeacaoNemOFim() {
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.EDIT_GROUP)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.MANAGE_TOPICS)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.MANAGE_MEMBERS)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.DECIDE_JOIN_REQUESTS)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.MODERATE_CONTENT)).isTrue();

		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.MANAGE_ROLES))
			.as("nomear administrador é do dono")
			.isFalse();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.TRANSFER_OWNERSHIP)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.ADMIN, GroupCapability.CLOSE_GROUP)).isFalse();
	}

	@Test
	void oModeradorCuidaDoConteudoENaoDaConfiguracao() {
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.MODERATE_CONTENT)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.PIN_CONTENT)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.PUBLISH)).isTrue();

		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.MANAGE_TOPICS)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.MANAGE_MEMBERS))
			.as("remover membro é de quem administra")
			.isFalse();
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.DECIDE_JOIN_REQUESTS)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.MODERATOR, GroupCapability.EDIT_GROUP)).isFalse();
	}

	@Test
	void oMembroParticipaENaoModera() {
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.VIEW_PRIVATE_CONTENT)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.PUBLISH)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.INTERACT)).isTrue();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.REPORT_CONTENT)).isTrue();

		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.MODERATE_CONTENT)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.VIEW_JOIN_REQUESTS)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.MANAGE_MEMBERS)).isFalse();
		assertThat(GroupPermissions.can(GroupRole.MEMBER, GroupCapability.EDIT_GROUP)).isFalse();
	}

	@Test
	void oVisitanteSoAlcancaAApresentacaoEDenunciaLa() {
		assertThat(GroupPermissions.can(null, GroupCapability.REPORT_GROUP)).isTrue();

		for (GroupCapability capability : DO_GRUPO) {
			if (capability == GroupCapability.REPORT_GROUP) {
				continue;
			}
			assertThat(GroupPermissions.can(null, capability))
				.as("visitante x %s", capability)
				.isFalse();
		}
	}

	@Test
	void asProibicoesValemParaTodosInclusiveODono() {
		List<GroupRole> papeis = List.of(GroupRole.OWNER, GroupRole.ADMIN, GroupRole.MODERATOR, GroupRole.MEMBER);
		for (GroupRole role : papeis) {
			for (GroupCapability capability : PROIBIDAS) {
				assertThat(GroupPermissions.can(role, capability))
					.as("%s x %s", role, capability)
					.isFalse();
			}
		}
		for (GroupCapability capability : PROIBIDAS) {
			assertThat(GroupPermissions.can(null, capability)).isFalse();
		}
	}

	@Test
	void oGrupoNuncaFicaSemAutoridade() {
		Instant agora = Instant.parse("2026-10-18T12:00:00Z");
		GroupMember dono = GroupMember.join("group_1", "user_dono", GroupRole.OWNER, agora);
		GroupMember outro = GroupMember.join("group_1", "user_outro", GroupRole.MEMBER, agora);

		assertThat(GroupPermissions.canDropOwnership(List.of(dono, outro), "user_dono"))
			.as("o único dono não sai")
			.isFalse();

		GroupMember segundoDono = GroupMember.join("group_1", "user_segundo", GroupRole.OWNER, agora);
		assertThat(GroupPermissions.canDropOwnership(List.of(dono, segundoDono), "user_dono"))
			.as("com outro dono, a saída é permitida")
			.isTrue();
	}
}
