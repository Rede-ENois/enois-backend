package com.socialcoisa.rede.network.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O que existe quando não há dump real configurado (ambiente de desenvolvimento, teste).
 *
 * <p>Sem este bean o contexto não sobe em lugar nenhum sem o {@code mariadb-dump}. Mas ele
 * <b>não finge cópia</b>: não devolve bytes. A cópia falha com motivo explícito, o nó fica sem
 * cópia {@code READY}, e o painel diz que está desligado — em vez de um dump de mentira que faria
 * um nó sem o banco aparecer como pronto para assumir.</p>
 */
@Configuration
class StandbyDumpFallbackConfiguration {

	@Bean
	@ConditionalOnMissingBean(StandbyDumpSource.class)
	StandbyDumpSource standbyDumpUnavailable() {
		return offset -> {
			throw new IllegalStateException("copia do banco desligada: defina rede.network.standby.dump.enabled=true"
				+ " e tenha o mariadb-dump disponivel");
		};
	}
}