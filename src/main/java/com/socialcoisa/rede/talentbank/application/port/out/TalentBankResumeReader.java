package com.socialcoisa.rede.talentbank.application.port.out;

import java.io.InputStream;
import java.util.Optional;

/**
 * A leitura do currículo de um candidato autorizado.
 *
 * <p>O arquivo nunca sai pelo endereço público do storage: a leitura acontece aqui, depois que o
 * módulo conferiu que a inscrição está ativa e que o currículo faz parte dos campos autorizados. O
 * que volta é o corpo do arquivo — sem URL, sem chave de bucket.</p>
 */
public interface TalentBankResumeReader {

	/**
	 * O currículo de quem é dono do arquivo.
	 *
	 * @param resumeUrl o endereço guardado no perfil
	 * @return vazio quando não há currículo, quando o endereço não é do storage daqui ou quando o
	 *         arquivo não pertence a quem o profile diz que é o dono
	 */
	Optional<ResumeFile> read(String candidateId, String resumeUrl, String resumeName);

	/** O arquivo e o nome que ele tem para download. */
	record ResumeFile(InputStream body, long length, String contentType, String fileName) {
	}
}
