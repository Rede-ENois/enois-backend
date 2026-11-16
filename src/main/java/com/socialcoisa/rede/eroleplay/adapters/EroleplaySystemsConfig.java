package com.socialcoisa.rede.eroleplay.adapters;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.eroleplay.domain.D20System;
import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;

/**
 * Publica os adapters de sistema de regras como beans.
 *
 * <p>Registrar um sistema novo é acrescentar um bean aqui — o {@code GameSystemRegistry} monta o
 * índice a partir dos adapters injetados, sem lista escrita à mão que envelhece. Um sistema
 * comercial só entra quando houver autorização de quem detém os direitos; até lá, o que a
 * plataforma publica é o motor D20 original do Éroleplay.</p>
 */
@Configuration(proxyBeanMethods = false)
class EroleplaySystemsConfig {

	@Bean
	SystemAdapter d20System() {
		return new D20System();
	}
}
