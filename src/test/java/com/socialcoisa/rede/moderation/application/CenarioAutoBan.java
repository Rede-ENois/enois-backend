package com.socialcoisa.rede.moderation.application;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

final class CenarioAutoBan implements AutoCloseable {

	private static final AtomicInteger PROXIMO_CLIENTE = new AtomicInteger(1);

	private final SuspiciousAutoBanWorker worker;
	private final JdbcTemplate jdbc;
	private final Set<String> bloqueiosCriados = new LinkedHashSet<>();
	private final String endereco;

	CenarioAutoBan(SuspiciousAutoBanWorker worker, JdbcTemplate jdbc) throws UnknownHostException {
		this.worker = worker;
		this.jdbc = jdbc;
		int cliente = PROXIMO_CLIENTE.getAndIncrement();
		if (cliente > 254) {
			throw new IllegalStateException("Faixa de clientes de teste esgotada");
		}
		// Faixa de documentação, sem DNS nem endereço de uma pessoa ou máquina real.
		this.endereco = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, (byte) cliente}).getHostAddress();
	}

	String endereco() {
		return endereco;
	}

	RequestPostProcessor cliente() {
		return request -> {
			request.setRemoteAddr(endereco);
			return request;
		};
	}

	SuspiciousAutoBanWorker.Report executarRodada() {
		var resultado = worker.sweep();
		bloqueiosCriados.addAll(resultado.blockedIps());
		return resultado;
	}

	@Override
	public void close() {
		// O relatório só inclui inserções desta rodada; bloqueios anteriores não pertencem à fixture.
		bloqueiosCriados.forEach(endereco -> jdbc.update("delete from blocked_ips where ip = ?", endereco));
		bloqueiosCriados.clear();
	}
}
