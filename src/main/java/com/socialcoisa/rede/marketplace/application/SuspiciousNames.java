package com.socialcoisa.rede.marketplace.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;

/**
 * Padrões da triagem de contas de teste/ataque ("probe" por padrão), aplicados ao nome e ao @ de
 * uma conta ou ao título de um item.
 *
 * <p>Existe porque as duas rotinas horárias do marketplace usam exatamente a mesma regra: uma para
 * decidir se a listagem é resquício, outra para decidir se o tema volta a ser listado. Duas cópias
 * divergiriam com o tempo, e aí a limpeza removeria o que o importador recria na mesma rodada.</p>
 */
final class SuspiciousNames {

	private final List<String> patterns;

	SuspiciousNames(String configuredPatterns) {
		this.patterns = Arrays.stream(configuredPatterns.split(","))
			.map(String::trim)
			.filter(pattern -> !pattern.isEmpty())
			.map(pattern -> pattern.toLowerCase(Locale.ROOT))
			.toList();
	}

	/** O texto (título de item, nome de tema) contém algum padrão da triagem. */
	boolean in(String text) {
		if (text == null || patterns.isEmpty()) {
			return false;
		}
		String typed = text.toLowerCase(Locale.ROOT);
		return patterns.stream().anyMatch(typed::contains);
	}

	/** O nome ou o @ da conta contém algum padrão da triagem. */
	boolean in(AccountIdentity account) {
		if (account == null) {
			return false;
		}
		return in(account.name() + " " + account.username());
	}
}