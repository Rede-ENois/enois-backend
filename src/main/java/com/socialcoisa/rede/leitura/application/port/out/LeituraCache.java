package com.socialcoisa.rede.leitura.application.port.out;

import java.util.Collection;
import java.util.Map;

import com.socialcoisa.rede.leitura.domain.LeituraTexto;

/**
 * O cache de leituras, indexado pelo hash do conteúdo. Salvar é idempotente: a mesma chave
 * sobrescreve, e o Papo Reto/Wrapped leem daqui — nunca do modelo no caminho da tela.
 */
public interface LeituraCache {

	/** As leituras já gravadas para os hashes informados (ausentes ficam fora do mapa). */
	Map<String, LeituraTexto> porHashes(Collection<String> hashes);

	/** Grava (ou sobrescreve) a leitura de um hash. */
	void salvar(String hash, LeituraTexto leitura, int versao);

	/** A quantas leituras são degradadas (sem modelo) e podem ser refeitas quando ele voltar. */
	long degradadas();
}
