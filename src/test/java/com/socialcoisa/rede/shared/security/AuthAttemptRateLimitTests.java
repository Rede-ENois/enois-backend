package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * O teto da autenticação por credencial que <b>falhou</b> — a chave de nó e a assinatura S3.
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li><b>só a falha conta</b> — se o acerto gastasse o orçamento, quem tem a chave certa seria
 * barrado pelo próprio sucesso (um nó que re-tenta por rede instável chegaria ao teto sozinho);</li>
 * <li><b>o teto é por credencial</b> — por {@code nodeId} e por Access Key, não por IP: quem ataca
 * troca de IP, e o alvo do teste é a credencial;</li>
 * <li><b>a janela expira</b> — passado o tempo, o orçamento volta; um bloqueio permanente puniria
 * quem errou a chave ao colar.</li>
 * </ul>
 */
class AuthAttemptRateLimitTests {

	/** Relógio que o teste move à mão: a janela é tempo, e tempo em teste não é espera. */
	private static final class Relogio extends Clock {

		private Instant agora;

		private Relogio(Instant inicio) {
			this.agora = inicio;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return agora;
		}
	}

	/**
	 * Três tentativas de nó por cinco minutos e vinte de S3 por cinco — bem abaixo do padrão para o
	 * teste chegar ao teto em três chamadas.
	 */
	private static IpRateLimiter limiter(Clock clock) {
		return new IpRateLimiter(clock, 5, 60, 20, 15, 5, 15, 5, 15, 20, 15, 5, 60,
			120, 15, 30, 60, 3, 60, 10, 15,
			/* nodeAuth */ 3, 5, /* s3Auth */ 20, 5, /* s3Request */ 4, 1);
	}

	private static IpRateLimiter limiter() {
		return limiter(new Relogio(Instant.parse("2026-11-19T12:00:00Z")));
	}

	@Test
	void o_terceiro_erro_ainda_passa_e_o_quarto_e_barrado() {
		IpRateLimiter teto = limiter();
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isTrue();
		teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isTrue();
		teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isTrue();
		teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		// Três falhas é o teto: a próxima tentativa não acontece.
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isFalse();
	}

	@Test
	void o_acerto_nao_gasta_o_orcamento_de_ninguem() {
		IpRateLimiter teto = limiter();
		// Quarenta verificações que deram certo (um nó saudável consultando a fila a cada 2 s).
		for (int i = 0; i < 40; i++) {
			assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_bom")).isTrue();
		}
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_bom")).isTrue();
	}

	@Test
	void o_bloqueio_e_da_credencial_e_nao_do_vizinho() {
		IpRateLimiter teto = limiter();
		for (int i = 0; i < 3; i++) {
			teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_alvo");
		}
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_alvo")).isFalse();
		// Outro nó no mesmo instante continua com o orçamento inteiro.
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_outro")).isTrue();
	}

	@Test
	void a_requisicao_legitima_tem_teto_por_credencial() {
		// Brief §3.3: o teto de assinatura inválida protege o segredo; este protege o Control Plane de
		// uma credencial legítima em laço. Contas diferentes, de propósito.
		IpRateLimiter teto = limiter();
		for (int i = 0; i < 4; i++) {
			assertThat(teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_LAÇO")).as("dentro do teto").isTrue();
		}
		assertThat(teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_LAÇO")).isFalse();
		// O balde é da credencial: outras continuam servindo, e a conta de assinatura não foi gasta.
		assertThat(teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_OUTRA")).isTrue();
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.S3_AUTH, "AKIA_LAÇO")).isTrue();
	}

	@Test
	void o_teto_de_requisicao_volta_passada_a_janela() {
		Relogio relogio = new Relogio(Instant.parse("2026-11-19T12:00:00Z"));
		IpRateLimiter teto = limiter(relogio);
		for (int i = 0; i < 4; i++) {
			teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_JANELA");
		}
		assertThat(teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_JANELA")).isFalse();
		relogio.agora = relogio.agora.plusSeconds(61);
		assertThat(teto.permiteRequisicao(IpRateLimiter.S3_REQUEST, "AKIA_JANELA"))
			.as("passada a janela o cliente legítimo volta a ser servido").isTrue();
	}

	@Test
	void as_duas_credenciais_tem_contas_separadas() {
		// Uma só chamada nunca pode gastar as duas: a assinatura S3 é de uma aplicação, a chave de nó
		// é de um participante, e os dois não compartilham destino.
		IpRateLimiter teto = limiter();
		for (int i = 0; i < 3; i++) {
			teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		}
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isFalse();
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.S3_AUTH, "AKIA_TENTATIVA")).isTrue();
	}

	@Test
	void passada_a_janela_o_orcamento_volta() {
		Relogio relogio = new Relogio(Instant.parse("2026-11-19T12:00:00Z"));
		IpRateLimiter teto = limiter(relogio);
		for (int i = 0; i < 3; i++) {
			teto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		}
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).as("ainda bloqueado").isFalse();

		relogio.agora = relogio.agora.plusSeconds(301);
		// Cinco minutos depois, as três falhas saíram da janela: o orçamento é o mesmo de antes.
		assertThat(teto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).as("liberado").isTrue();
	}

	@Test
	void teto_zero_desliga_o_limitador() {
		// 0 é a forma de desligar sem uma flag própria — a operação que confia na borda pode querer.
		IpRateLimiter semTeto = new IpRateLimiter(new Relogio(Instant.parse("2026-11-19T12:00:00Z")),
			5, 60, 20, 15, 5, 15, 5, 15, 20, 15, 5, 60, 120, 15, 30, 60, 3, 60, 10, 15,
			0, 5, 0, 5, 0, 1);
		for (int i = 0; i < 100; i++) {
			semTeto.registraFalhaDeAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1");
		}
		assertThat(semTeto.permiteTentarAutenticacao(IpRateLimiter.NODE_AUTH, "nd_1")).isTrue();
	}
}