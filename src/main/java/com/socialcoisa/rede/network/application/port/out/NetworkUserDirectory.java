package com.socialcoisa.rede.network.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Leitura mínima das contas para o painel de nós: nome e e-mail de quem responde.
 *
 * <p>É porta do módulo da rede, implementada por consulta direta, e não uma dependência do serviço
 * de identidade inteiro: o painel precisa de três colunas, e acoplar os dois módulos por isso
 * transformaria qualquer mudança de identidade em mudança da rede.</p>
 */
public interface NetworkUserDirectory {

	record Account(String id, String name, String email) {
	}

	Optional<Account> byId(String id);

	Optional<Account> byEmail(String email);

	Map<String, Account> byIds(Collection<String> ids);
}