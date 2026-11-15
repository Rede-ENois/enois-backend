package com.socialcoisa.rede.network.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * O mapa perfil → tipo de tarefa é a autorização real: capacidade computacional não dá permissão
 * (doc 04 §1). Estes testes fixam o mapa para que um tipo novo não seja autorizado "de carona" e
 * um perfil novo não receba tarefa sem alguém ter decidido isso.
 */
class TaskTypeAuthorizationTests {

	@Test
	void cadaTipoExigeExatamenteUmPerfilENodeLocalCobreTodos() {
		for (TaskType type : TaskType.values()) {
			assertTrue(type.allowedFor(Set.of(type.requiredProfile())),
				() -> type + " deve ser liberado pelo próprio perfil");
			assertTrue(type.allowedFor(Set.of(NodeProfile.NODE_LOCAL)),
				() -> type + " deve ser liberado por NODE_LOCAL (prioridade de infraestrutura)");
		}
	}

	@Test
	void perfilSemRelacaoNaoLiberaTipoNenhum() {
		// Um nó só de borda não recebe transcode; um só de mídia não recebe cache nem requisição de API.
		assertFalse(TaskType.MEDIA_TRANSCODE.allowedFor(Set.of(NodeProfile.NODE_EDGE)));
		assertFalse(TaskType.EDGE_CACHE.allowedFor(Set.of(NodeProfile.NODE_MEDIA)));
		assertFalse(TaskType.READ_QUERY.allowedFor(Set.of(NodeProfile.NODE_WORKER)));
		assertFalse(TaskType.API_REQUEST.allowedFor(Set.of(NodeProfile.NODE_READ_REPLICA)));
	}

	@Test
	void perfilVazioOuNuloNaoAutorizaNada() {
		for (TaskType type : TaskType.values()) {
			assertFalse(type.allowedFor(Set.of()), () -> type + " autorizou com perfil vazio");
			assertFalse(type.allowedFor(null), () -> type + " autorizou com perfil nulo");
		}
	}

	/**
	 * A lista que vai na config assinada tem de ser a MESMA do predicado de despacho — senão a
	 * config diz que o nó pode uma coisa e o scheduler decide outra. A regra esperada é reescrita
	 * aqui a partir de `requiredProfile()`: comparar `allowedTypesFor` com `allowedFor` seria
	 * tautologia (as duas implementações do mesmo arquivo concordariam mesmo erradas).
	 */
	@Test
	void listaDaConfigAssinadaConcordaComOPredicadoDeDespacho() {
		for (NodeProfile profile : NodeProfile.values()) {
			Set<NodeProfile> profiles = Set.of(profile);
			List<TaskType> daConfig = TaskType.allowedTypesFor(profiles);
			List<TaskType> esperado = Arrays.stream(TaskType.values())
				.filter(type -> type.requiredProfile() == profile || profile == NodeProfile.NODE_LOCAL)
				.toList();
			assertEquals(esperado, daConfig, () -> "divergiu para " + profile);
		}
	}

	/**
	 * O Storage entrou na fase 1 (docs/rede-distribuida/12-storage-fase-1.md) com perfil e tipos
	 * PRÓPRIOS. A invariante que a fase 0 fixou continua valendo e é o que este teste guarda agora:
	 * armazenamento nunca é autorizado por um perfil existente — só por {@code NODE_STORAGE}, e
	 * {@code NODE_STORAGE} não autoriza nada além de armazenamento. Se alguém acrescentar
	 * {@code OBJECT_PUT} ao {@code NODE_MEDIA} "porque é conveniente", o nó de mídia passaria a
	 * guardar bytes de terceiros sem o participante ter consentido com disco.
	 */
	@Test
	void armazenamentoSoEhAutorizadoPeloPerfilProprioDeStorage() {
		Set<NodeProfile> deHoje = Arrays.stream(NodeProfile.values())
			.filter(profile -> profile != NodeProfile.NODE_STORAGE && profile != NodeProfile.NODE_LOCAL)
			.collect(Collectors.toSet());

		for (NodeProfile profile : deHoje) {
			for (TaskType tipo : TaskType.values()) {
				String nome = tipo.name();
				if (!nome.equals("OBJECT_PUT") && !nome.equals("OBJECT_GET")) {
					continue;
				}
				assertFalse(tipo.allowedFor(Set.of(profile)),
					nome + " não pode ser autorizado por " + profile + " — só por NODE_STORAGE");
			}
		}

		// E o inverso: o perfil de storage não é um curinga. Ele autoriza armazenamento e nada mais,
		// para que um nó contribuído com disco não comece a executar transcode ou leitura de dados.
		Set<String> doStorage = TaskType.allowedTypesFor(Set.of(NodeProfile.NODE_STORAGE)).stream()
			.map(Enum::name).collect(Collectors.toSet());
		assertEquals(Set.of("OBJECT_PUT", "OBJECT_GET"), doStorage,
			"o perfil de storage autoriza exatamente os tipos de armazenamento");
	}
}