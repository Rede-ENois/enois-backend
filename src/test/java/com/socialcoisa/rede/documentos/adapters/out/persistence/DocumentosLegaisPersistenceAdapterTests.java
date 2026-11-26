package com.socialcoisa.rede.documentos.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

/**
 * A corrida do aceite: duas aceitações da mesma versão ao mesmo tempo.
 *
 * <p>A segunda inserção viola o índice único {@code (user_id, documento_id)} — o banco decide
 * qual das duas grava. A que perdeu tem de devolver o aceite que a outra gravou (o {@code 200}
 * idempotente prometido), não estourar o erro para quem aceitou o texto.</p>
 */
@SpringBootTest
class DocumentosLegaisPersistenceAdapterTests {

	private static final String USER_ID = "user_corrida_aceite";
	private static final String DOCUMENTO_ID = "doc_corrida_aceite";

	@Autowired
	private DocumentosLegaisPersistenceAdapter adapter;

	@Autowired
	private SpringDataAceiteDeDocumentoJpaRepository aceites;

	@Test
	void devolve_o_aceite_que_a_outra_aceitacao_gravou_quando_o_indice_unico_viola() {
		AceiteDeDocumento primeiro = new AceiteDeDocumento("dlaceite_corrida_1", DOCUMENTO_ID, USER_ID,
			DocumentoTipo.TERMOS_DE_USO, 1, "a".repeat(64), AceiteDeDocumento.Origem.AceiteExplicito,
			Instant.parse("2026-01-01T00:00:00Z"), null, null);
		aceites.saveAndFlush(AceiteDeDocumentoJpaEntity.from(primeiro));

		AceiteDeDocumento segundo = new AceiteDeDocumento("dlaceite_corrida_2", DOCUMENTO_ID, USER_ID,
			DocumentoTipo.TERMOS_DE_USO, 1, "a".repeat(64), AceiteDeDocumento.Origem.AceiteExplicito,
			Instant.parse("2026-01-01T00:00:01Z"), null, null);

		AceiteDeDocumento devolvido = adapter.saveAceite(segundo);

		assertThat(devolvido.id()).isEqualTo(primeiro.id());
		AceiteDeDocumento gravado = aceites.findByUserIdAndDocumentoId(USER_ID, DOCUMENTO_ID)
			.orElseThrow()
			.toDomain();
		assertThat(gravado.id()).isEqualTo(primeiro.id());
	}
}
