package com.socialcoisa.rede.support.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportRequest;

/**
 * O e-mail de aviso do suporte nao pode ser enviado com a transacao do pedido aberta.
 *
 * <p>O envio e SMTP sincrono ({@code mailSender.send}), e o metodo inteiro era {@code @Transactional}:
 * a conexao do banco ficava presa durante o handshake com o servidor de e-mail. Com o SMTP lento ou
 * fora do ar, cada abertura de pedido segurava uma conexao do Hikari por segundos — o mesmo risco
 * que o projeto ja evita em outros caminhos, publicando o efeito externo DEPOIS do commit
 * ({@code SupportGithubSync} segue exatamente esse padrao para a issue no GitHub).</p>
 *
 * <p>A assercao e de comportamento: o envio tem de acontecer FORA de qualquer transacao ativa.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.support.email=suporte@enois.test" })
class SupportEmailTransactionTests {

	/** Registra, no momento da chamada, se havia transacao ativa no thread. */
	static final List<Boolean> INSIDE_TRANSACTION = new ArrayList<>();
	static final List<String> CHAMADAS = new ArrayList<>();

	@TestConfiguration
	static class EmailProbeConfig {

		/**
		 * Substitui o {@link EmailService} do contexto por um que so registra o instante da chamada.
		 * Nao chama o original: aqui importa QUANDO o envio aconteceria, nao entregar e-mail.
		 */
		@Bean
		@Primary
		EmailService probingEmailService() {
			return new EmailService(null, null, "https://app.test", "https://api.test", "no-reply@test",
				null) {
				@Override
				public void sendSupportRequestEmail(String toEmail, String requestSubject, String requestMessage,
						String replyTo) {
					CHAMADAS.add("open");
					var recurso = TransactionSynchronizationManager.getResource(
						jakarta.persistence.EntityManagerFactory.class);
					INSIDE_TRANSACTION.add(recurso != null);
				}

				@Override
				public void sendSupportReplyEmail(String toEmail, String originalSubject, String reply) {
					CHAMADAS.add("reply");
					INSIDE_TRANSACTION.add(TransactionSynchronizationManager.getResource(
						jakarta.persistence.EntityManagerFactory.class) != null);
				}
			};
		}
	}

	@Autowired
	private SupportService support;

	@Test
	void theSupportEmailIsSentOutsideTheTransaction() {
		INSIDE_TRANSACTION.clear();
		CHAMADAS.clear();

		support.open("sup_tx_" + UUID.randomUUID().toString().substring(0, 8), "Assunto do teste",
			"Descricao com detalhe suficiente para passar da validacao minima.", "pessoa@teste.com", null,
			SupportCategory.OTHER);

		assertThat(CHAMADAS)
			.as("o aviso sai UMA vez por pedido (pelo listener pos-commit)")
			.containsExactly("open");
		assertThat(INSIDE_TRANSACTION)
			.as("o envio de e-mail precisa correr FORA da transacao do pedido")
			.isNotEmpty()
			.doesNotContain(true);
	}

	@Test
	void theSupportReplyEmailIsSentOutsideTheTransaction() {
		SupportRequest pedido = support.open("sup_rep_" + UUID.randomUUID().toString().substring(0, 8),
			"Assunto da resposta", "Descricao com detalhe suficiente para passar da validacao minima.",
			"pessoa@teste.com", null, SupportCategory.OTHER);
		INSIDE_TRANSACTION.clear();
		CHAMADAS.clear();

		support.reply(pedido.id(), "admin_teste", "Resposta do time com detalhe suficiente.");

		assertThat(CHAMADAS)
			.as("a resposta sai UMA vez (pelo listener pos-commit)")
			.containsExactly("reply");
		assertThat(INSIDE_TRANSACTION)
			.as("a resposta por e-mail tambem corre FORA da transacao")
			.isNotEmpty()
			.doesNotContain(true);
	}
}