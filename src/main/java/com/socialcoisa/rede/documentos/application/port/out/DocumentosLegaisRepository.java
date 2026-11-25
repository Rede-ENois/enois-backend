package com.socialcoisa.rede.documentos.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

/** Persistência das versões dos documentos e dos aceites. */
public interface DocumentosLegaisRepository {

	/** Versão em vigor de cada tipo informado (a que está valendo agora). */
	List<DocumentoLegal> vigentes(Collection<DocumentoTipo> tipos);

	Optional<DocumentoLegal> vigente(DocumentoTipo tipo);

	Optional<DocumentoLegal> porId(String id);

	/** O rascunho (aprovado, ainda sem vigência) mais recente de cada tipo, se houver. */
	List<DocumentoLegal> emRevisao(Collection<DocumentoTipo> tipos);

	Optional<DocumentoLegal> versao(DocumentoTipo tipo, int versao);

	int proximaVersao(DocumentoTipo tipo);

	List<DocumentoLegal> historico(DocumentoTipo tipo);

	DocumentoLegal save(DocumentoLegal documento);

	Optional<AceiteDeDocumento> aceite(String userId, String documentoId);

	List<AceiteDeDocumento> aceitesDoUsuario(String userId);

	AceiteDeDocumento saveAceite(AceiteDeDocumento aceite);

	/**
	 * Encerra a vigência de todas as versões em vigor do tipo no instante anterior ao informado:
	 * é o que faz uma versão nova substituir a anterior sem apagar o histórico. Devolve quantas
	 * mudaram.
	 */
	int encerrarVigencia(DocumentoTipo tipo, Instant quando);
}
