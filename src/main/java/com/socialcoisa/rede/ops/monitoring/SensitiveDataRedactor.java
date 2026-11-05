package com.socialcoisa.rede.ops.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials and personal data from free text before it is stored in the panel or sent
 * to an external channel. Applied to messages and stack traces; request bodies and headers are
 * never collected in the first place.
 *
 * <p>Order matters: whole blocks (private keys) first, then structured secrets (JWT, bot tokens,
 * cloud keys), then key/value pairs, then personal data (e-mail, card, CPF, phone, IP).</p>
 */
public final class SensitiveDataRedactor {

	private static final Pattern PRIVATE_KEY = Pattern.compile(
		"-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
	private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
	private static final Pattern BOT_TOKEN = Pattern.compile("\\b\\d{6,12}:[A-Za-z0-9_-]{30,}");
	private static final Pattern CLOUD_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b|\\bgh[pousr]_[A-Za-z0-9]{20,}\\b");
	private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}");
	private static final Pattern KEY_VALUE = Pattern.compile("(?i)\\b(authorization|proxy-authorization|cookie|set-cookie"
		+ "|x-session-token|session[_-]?token|session[_-]?id|jsessionid|api[_-]?key|apikey|access[_-]?token"
		+ "|refresh[_-]?token|id[_-]?token|token|secret|client[_-]?secret|private[_-]?key|password|passwd|pwd|senha"
		+ "|otp|cvv|cvc|card[_-]?number|iban|agencia|conta)(\"?\\s*[=:]\\s*\"?)(?!(?:bearer|basic)\\b)([^\\s,;&\"']+)");
	/** "token abc123" (no separator): the panel always redacted this form; kept for the same words. */
	private static final Pattern SPACED_SECRET = Pattern.compile("(?i)\\b(token|refresh_token|access_token|senha|password"
		+ "|passwd|secret)(\\s+)(?!(?:bearer|basic|\\*\\*\\*)\\b)([^\\s,;&\"']+)");
	private static final Pattern QUERY_VALUE = Pattern.compile("([?&][A-Za-z0-9_.-]{1,40}=)[^&\\s#]+");
	private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
	private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
	private static final Pattern CPF_CNPJ = Pattern.compile(
		"\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b|\\b\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}\\b|\\b\\d{11}\\b");
	private static final Pattern PHONE = Pattern.compile("(?:\\+?55\\s?)?\\(?\\b\\d{2}\\)?\\s?9\\d{4}-?\\d{4}\\b");
	private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

	private SensitiveDataRedactor() {
	}

	/** Redacts a single-line message: newlines and tabs become spaces. */
	public static String redactLine(String text) {
		return text == null ? "" : redact(text).replace('\n', ' ').replace('\t', ' ');
	}

	/** Redacts multi-line text (stack traces) keeping line breaks. */
	public static String redact(String text) {
		if (text == null || text.isEmpty()) {
			return text == null ? "" : text;
		}
		String clean = PRIVATE_KEY.matcher(text).replaceAll("[private key removed]");
		clean = JWT.matcher(clean).replaceAll("***");
		clean = BOT_TOKEN.matcher(clean).replaceAll("***");
		clean = CLOUD_KEY.matcher(clean).replaceAll("***");
		clean = AUTH_SCHEME.matcher(clean).replaceAll("$1 ***");
		clean = KEY_VALUE.matcher(clean).replaceAll("$1$2***");
		clean = SPACED_SECRET.matcher(clean).replaceAll("$1$2***");
		clean = QUERY_VALUE.matcher(clean).replaceAll("$1***");
		clean = EMAIL.matcher(clean).replaceAll("***@***");
		clean = maskCards(clean);
		clean = CPF_CNPJ.matcher(clean).replaceAll("***");
		clean = PHONE.matcher(clean).replaceAll("***");
		clean = IPV4.matcher(clean).replaceAll("***");
		return CONTROL.matcher(clean).replaceAll(" ");
	}

	/** Only digit runs that pass Luhn are cards: epoch millis and numeric ids stay readable. */
	private static String maskCards(String text) {
		Matcher matcher = CARD.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String digits = matcher.group().replaceAll("[ -]", "");
			matcher.appendReplacement(out, digits.length() >= 13 && luhn(digits) ? "***" : Matcher.quoteReplacement(matcher.group()));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private static boolean luhn(String digits) {
		int sum = 0;
		boolean doubled = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int digit = digits.charAt(i) - '0';
			if (doubled) {
				digit *= 2;
				if (digit > 9) {
					digit -= 9;
				}
			}
			sum += digit;
			doubled = !doubled;
		}
		return sum % 10 == 0;
	}
}
