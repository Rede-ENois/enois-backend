package com.socialcoisa.rede.identity.application;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;
import com.socialcoisa.rede.ops.domain.EmailLog;
import com.socialcoisa.rede.ops.domain.EmailLogStatus;
import com.socialcoisa.rede.shared.web.PiiMask;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String frontendUrl;
    private final String backendUrl;
    private final String fromEmail;
    private final EmailLogRepository emailLogs;

    public EmailService(
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${rede.frontend.url}") String frontendUrl,
            @Value("${rede.backend.url:https://api.enois.ia.br}") String backendUrl,
            @Value("${spring.mail.username}") String fromEmail,
            EmailLogRepository emailLogs) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.frontendUrl = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
        this.backendUrl = backendUrl.endsWith("/")
                ? backendUrl.substring(0, backendUrl.length() - 1)
                : backendUrl;
        this.fromEmail = fromEmail;
        this.emailLogs = emailLogs;
    }

    /**
     * Confirmação do NOVO e-mail (E-02/E-11). O endereço só passa a valer quando a pessoa clica
     * neste link — e o clique prova que ela controla a caixa nova.
     */
    public boolean sendEmailChangeConfirmation(String toEmail, String userName, String token) {
        String link = backendUrl + "/api/auth/email/change/confirm?token="
                + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        String htmlBody = "<p>Olá, " + escapeHtml(userName) + ".</p>"
                + "<p>Recebemos um pedido para trocar o e-mail da sua conta no Énóis para este endereço.</p>"
                + "<p><a href=\"" + escapeHtml(link) + "\" style=\"display:inline-block;padding:12px 24px;"
                + "background:#6366f1;color:#fff;text-decoration:none;border-radius:999px;font-weight:600\">"
                + "Confirmar novo e-mail</a></p>"
                + "<p>Se não foi você, ignore esta mensagem: nada muda até o clique.</p>"
                + "<p>— Equipe Énóis</p>";
        return sendHtml(toEmail, "Confirme seu novo e-mail — Énóis", htmlBody);
    }

    /**
     * Sent instead of a verification link when someone signs up with an address that already has an
     * account: the owner learns about it, and the signup response stays the same as a new one.
     */
    public boolean sendSignupAttemptNotice(String toEmail, String userName) {
        String htmlBody = "<p>Olá, " + escapeHtml(userName) + ".</p>"
                + "<p>Alguém tentou criar uma conta no Énóis com este e-mail, mas ele já tem uma conta.</p>"
                + "<p>Se foi você, <a href=\"" + escapeHtml(frontendUrl + "/entrar") + "\">entre</a> normalmente "
                + "ou use \"Esqueci minha senha\". Se não foi, pode ignorar esta mensagem: nada mudou na sua conta.</p>"
                + "<p>— Equipe Énóis</p>";
        return sendHtml(toEmail, "Tentativa de cadastro com seu e-mail — Énóis", htmlBody);
    }

    /**
     * Registra o metadado do envio sem nunca atrapalhar o envio em si: se o log falhar, o e-mail
     * (que é o que importa) segue normalmente.
     */
    private void recordEmail(String toEmail, String subject, String kind, boolean sent, String error) {
        try {
            // O código do 2FA NUNCA entra no log: o assunto é redigido por tipo, mesmo que um
            // chamador futuro monte um assunto com o código dentro.
            String safeSubject = "TWO_FACTOR".equals(kind) ? "Seu código de acesso (redigido)" : subject;
            emailLogs.save(EmailLog.create("email_" + UUID.randomUUID().toString().replace("-", ""),
                truncate(toEmail, 180), truncate(safeSubject, 200), kind,
                sent ? EmailLogStatus.SENT : EmailLogStatus.FAILED, truncate(error, 500), Instant.now()));
        } catch (RuntimeException logFailure) {
            LOG.warn("Nao foi possivel registrar o envio de e-mail para {}: {}", PiiMask.email(toEmail),
                PiiMask.text(logFailure.getMessage()));
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public void sendPasswordResetEmail(String toEmail, String userName, String resetToken) {
        String resetLink = buildResetLink(resetToken);
        String subject = "Redefinição de senha";
        String htmlBody = renderTemplate(userName, resetLink);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            recordEmail(toEmail, subject, "PASSWORD_RESET", true, null);
        } catch (Exception e) {
            recordEmail(toEmail, subject, "PASSWORD_RESET", false, e.getMessage());
            LOG.error("Falha ao enviar email de reset para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
        }
    }

    /**
     * Envia o link de confirmacao do e-mail do cadastro.
     *
     * @return false quando o envio falha — a conta continua pendente e a tela oferece reenviar,
     *         em vez de deixar o usuario esperando um e-mail que nunca chegou
     */
    public boolean sendVerificationEmail(String toEmail, String userName, String verificationToken) {
        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("verificationLink", buildVerificationLink(verificationToken));
        context.setVariable("currentYear", java.time.Year.now().getValue());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Confirme seu e-mail no Énóis");
            helper.setText(templateEngine.process("email-verification", context), true);
            mailSender.send(message);
            recordEmail(toEmail, "Confirme seu e-mail no Énóis", "EMAIL_VERIFICATION", true, null);
            return true;
        } catch (Exception e) {
            recordEmail(toEmail, "Confirme seu e-mail no Énóis", "EMAIL_VERIFICATION", false, e.getMessage());
            LOG.error("Falha ao enviar email de confirmacao para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Codigo do segundo fator por e-mail.
     *
     * @return false quando o envio falha — o login com 2FA precisa saber que o codigo nao saiu, em
     *         vez de deixar o usuario tentando um codigo que nunca chegou
     */
    public boolean sendTwoFactorCode(String toEmail, String userName, String code) {
        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("code", code);
        context.setVariable("currentYear", java.time.Year.now().getValue());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Seu código de acesso ao Énóis");
            helper.setText(templateEngine.process("two-factor-code", context), true);
            mailSender.send(message);
            recordEmail(toEmail, "Seu código de acesso ao Énóis", "TWO_FACTOR", true, null);
            return true;
        } catch (Exception e) {
            recordEmail(toEmail, "Seu código de acesso ao Énóis", "TWO_FACTOR", false, e.getMessage());
            LOG.error("Falha ao enviar o codigo do segundo fator para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Convite de supervisao parental (ECA Digital): o responsavel abre o link, entra na propria
     * conta e aceita ou recusa. O token vale 7 dias e e de uso unico.
     */
    public boolean sendParentalConsentEmail(String guardianEmail, String minorName, String token) {
        Context context = new Context();
        context.setVariable("minorName", minorName);
        context.setVariable("consentLink", frontendUrl + "/responsavel?token=" + token);
        context.setVariable("currentYear", java.time.Year.now().getValue());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(guardianEmail);
            helper.setSubject("Autorizacao de responsavel no Enois");
            helper.setText(templateEngine.process("parental-consent", context), true);
            mailSender.send(message);
            recordEmail(guardianEmail, "Autorizacao de responsavel no Enois", "PARENTAL_CONSENT", true, null);
            return true;
        } catch (Exception e) {
            recordEmail(guardianEmail, "Autorizacao de responsavel no Enois", "PARENTAL_CONSENT", false,
                e.getMessage());
            LOG.error("Falha ao enviar o convite de supervisao parental: {}", PiiMask.text(e.getMessage()));
            return false;
        }
    }

    /**
     * Envio de um HTML ja montado pelo chamador.
     *
     * Existe para modulos que precisam de um e-mail proprio (ex.: candidatura de vaga) sem que a
     * identidade precise conhecer o dominio deles.
     *
     * @return false quando o envio falha (quem chamou decide o que fazer; o dado ja esta persistido)
     */
    public boolean sendHtml(String toEmail, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            recordEmail(toEmail, subject, "HTML", true, null);
            return true;
        } catch (Exception e) {
            recordEmail(toEmail, subject, "HTML", false, e.getMessage());
            LOG.error("Falha ao enviar email para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * HTML com remetente NOMEADO ("{blog} via Énóis"), Reply-To e List-Unsubscribe.
     *
     * <p>É o que permite um blog enviar aos assinantes sem credencial de ninguém: o envio continua
     * sendo da plataforma (SMTP do Énóis), e a resposta do leitor cai direto no e-mail do dono.</p>
     */
    public boolean sendHtml(String toEmail, String subject, String htmlBody, String fromName, String replyTo,
            String listUnsubscribeUrl, String kind) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (org.springframework.util.StringUtils.hasText(fromName)) {
                helper.setFrom(new jakarta.mail.internet.InternetAddress(fromEmail, fromName, "UTF-8"));
            } else {
                helper.setFrom(fromEmail);
            }
            helper.setTo(toEmail);
            if (org.springframework.util.StringUtils.hasText(replyTo)) {
                helper.setReplyTo(replyTo);
            }
            if (org.springframework.util.StringUtils.hasText(listUnsubscribeUrl)) {
                message.setHeader("List-Unsubscribe", "<" + listUnsubscribeUrl + ">");
                // RFC 8058 one-click: the mail client POSTs to the same URL, so every caller of this
                // overload must also serve POST there (blog newsletter and weekly digest do).
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            recordEmail(toEmail, subject, kind, true, null);
            return true;
        } catch (Exception e) {
            recordEmail(toEmail, subject, kind, false, e.getMessage());
            LOG.error("Falha ao enviar email para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
            return false;
        }
    }

    private String buildResetLink(String resetToken) {
        return frontendUrl + "/auth/reset?token=" + resetToken;
    }

    private String buildVerificationLink(String verificationToken) {
        return frontendUrl + "/auth/verify?token=" + verificationToken;
    }

    private String renderTemplate(String userName, String resetLink) {
        Context context = new Context();
        context.setVariable("userName", userName);
        context.setVariable("resetLink", resetLink);
        context.setVariable("currentYear", java.time.Year.now().getValue());
        return templateEngine.process("password-reset", context);
    }

	/** Notifica o time de suporte sobre um pedido aberto no app (responde para o usuario). */
	public void sendSupportRequestEmail(String toEmail, String requestSubject, String requestMessage, String replyTo) {
		sendSupportRequestEmail(toEmail, requestSubject, requestMessage, replyTo, null, null);
	}

	/**
	 * Suporte com ANEXO DE IMAGEM: o print que a pessoa juntou ao pedido. A imagem vem
	 * como bytes (baixados do CDN pelo chamador); sem imagem, cai no e-mail simples.
	 */
	public void sendSupportRequestEmail(String toEmail, String requestSubject, String requestMessage, String replyTo,
			byte[] imageBytes, String imageName) {
		String htmlBody = "<p>Novo pedido de suporte enviado pelo app.</p>"
			+ "<ul>"
			+ "<li><b>Usuario:</b> " + escapeHtml(replyTo) + "</li>"
			+ "<li><b>Assunto:</b> " + escapeHtml(requestSubject) + "</li>"
			+ "</ul>"
			+ "<p><b>Mensagem:</b></p><pre style=\"white-space:pre-wrap\">" + escapeHtml(requestMessage)
			+ "</pre>";

		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(fromEmail, "Suporte Énóis", "UTF-8"));
			helper.setTo(toEmail);
			if (replyTo != null && replyTo.contains("@")) {
				helper.setReplyTo(replyTo);
			}
			helper.setSubject("[Suporte] " + requestSubject);
			helper.setText(htmlBody, true);
			if (imageBytes != null && imageBytes.length > 0) {
				helper.addAttachment(imageName == null ? "anexo.png" : imageName,
					new org.springframework.core.io.ByteArrayResource(imageBytes));
			}
			mailSender.send(message);
			recordEmail(toEmail, "[Suporte] " + requestSubject, "SUPPORT", true, null);
		} catch (Exception e) {
			recordEmail(toEmail, "[Suporte] " + requestSubject, "SUPPORT", false, e.getMessage());
			// O pedido ja esta persistido (aparece em /admin/support): apenas registramos a falha.
			LOG.error("Falha ao notificar o suporte ({}) sobre o pedido: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
		}
	}

	/**
	 * Resposta oficial do suporte ao solicitante. O remetente aparece como "Suporte Énóis" e a
	 * resposta é registrada em metadados (sem o corpo) no log do painel.
	 */
	public void sendSupportReplyEmail(String toEmail, String originalSubject, String reply) {
		String subject = "Resposta do suporte do Énóis — " + (originalSubject == null ? "" : originalSubject);
		String htmlBody = "<p>Olá,</p>"
			+ "<p>Sobre o seu pedido <b>" + escapeHtml(originalSubject) + "</b>:</p>"
			+ "<blockquote style=\"margin:8px 0;padding:8px 12px;border-left:3px solid #ccc;"
			+ "white-space:pre-wrap\">" + escapeHtml(reply) + "</blockquote>"
			+ "<p>— Equipe Énóis</p>";
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(fromEmail, "Suporte Énóis", "UTF-8"));
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(htmlBody, true);
			mailSender.send(message);
			recordEmail(toEmail, subject, "SUPPORT_REPLY", true, null);
		} catch (Exception e) {
			recordEmail(toEmail, subject, "SUPPORT_REPLY", false, e.getMessage());
			LOG.error("Falha ao enviar resposta do suporte para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
		}
	}

	/**
	 * Pedido concluído (fechado no painel ou na issue do GitHub): avisa quem abriu. Sem corpo
	 * livre — só o assunto do ticket — para não repetir dado pessoal no e-mail.
	 */
	public void sendSupportClosedEmail(String toEmail, String originalSubject) {
		String subject = "Pedido de suporte concluído — " + (originalSubject == null ? "" : originalSubject);
		String htmlBody = "<p>Olá,</p>"
			+ "<p>Seu pedido <b>" + escapeHtml(originalSubject) + "</b> foi concluído pelo time do Énóis.</p>"
			+ "<p>Se o problema continuar, é só abrir um novo pedido em "
			+ "<a href=\"https://enois.ia.br/suporte\">enois.ia.br/suporte</a>.</p>"
			+ "<p>— Equipe Énóis</p>";
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(fromEmail, "Suporte Énóis", "UTF-8"));
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(htmlBody, true);
			mailSender.send(message);
			recordEmail(toEmail, subject, "SUPPORT_CLOSED", true, null);
		} catch (Exception e) {
			recordEmail(toEmail, subject, "SUPPORT_CLOSED", false, e.getMessage());
			LOG.error("Falha ao avisar o encerramento do suporte para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
		}
	}

	/** E-mail HTML pronto (o chamador escapa o conteudo do usuario). Best-effort: nunca lanca. */
	public void sendHtmlEmail(String toEmail, String subject, String htmlBody) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(fromEmail);
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(htmlBody, true);
			mailSender.send(message);
			recordEmail(toEmail, subject, "HTML", true, null);
		} catch (Exception e) {
			recordEmail(toEmail, subject, "HTML", false, e.getMessage());
			LOG.error("Falha ao enviar e-mail para {}: {} ({})", PiiMask.email(toEmail), PiiMask.text(e.getMessage()), e.getClass().getSimpleName());
		}
	}

	/**
	 * The one escaping helper for e-mail bodies built as strings: every value that did not come
	 * from a literal in code (names, titles, user text, links) goes through it before entering the
	 * HTML. Thymeleaf templates escape on their own via {@code th:text}/{@code th:href}.
	 */
	public static String escapeHtml(String value) {
		return value == null ? "" : org.springframework.web.util.HtmlUtils.htmlEscape(value, "UTF-8");
	}
}
