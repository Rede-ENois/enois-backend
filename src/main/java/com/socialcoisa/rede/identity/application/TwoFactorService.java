package com.socialcoisa.rede.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.port.in.TwoFactorUseCase;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.StoredCredential;
import com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository;
import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;
import com.socialcoisa.rede.identity.domain.TwoFactorMethod;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.security.SecretCipher;
import com.socialcoisa.rede.shared.security.Totp;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Segundo fator: aplicativo authenticator (TOTP) ou codigo por e-mail.
 *
 * Regras que valem a pena ter em mente:
 * - o segredo do TOTP so e ativado depois de um codigo conferido (o setup sozinho nao liga nada);
 * - o codigo por e-mail e guardado como hash e vale uma vez;
 * - o login com segundo fator devolve um DESAFIO (id opaco), nunca sessao: a sessao so sai em
 *   {@link #verifyChallenge}, com o codigo certo, dentro da validade e antes do limite de tentativas;
 * - a ativacao/desativacao exige a senha (ou um codigo valido), para ninguem ligar/desligar o
 *   segundo fator a partir de uma sessao roubada sem mais nada.
 */
@Service
public class TwoFactorService implements TwoFactorUseCase {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(TwoFactorService.class);
	private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
	private static final Duration PENDING_SECRET_TTL = Duration.ofMinutes(15);
	private static final int BACKUP_CODE_COUNT = 8;
	private static final String ISSUER = "Énóis";

	private final TwoFactorRepository repository;
	private final UserProfileRepository userProfileRepository;
	private final CredentialRepository credentialRepository;
	private final PasswordHasher passwordHasher;
	private final SecretCipher secretCipher;
	private final EmailService emailService;
	private final AuthSessionRepository sessions;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	public TwoFactorService(TwoFactorRepository repository, UserProfileRepository userProfileRepository,
			CredentialRepository credentialRepository, PasswordHasher passwordHasher, SecretCipher secretCipher,
			EmailService emailService, AuthSessionRepository sessions, Clock clock) {
		this.repository = repository;
		this.userProfileRepository = userProfileRepository;
		this.credentialRepository = credentialRepository;
		this.passwordHasher = passwordHasher;
		this.secretCipher = secretCipher;
		this.emailService = emailService;
		this.sessions = sessions;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ status

	@Override
	public TwoFactorStatus status(String userId) {
		UserProfile profile = requireProfile(userId);
		return new TwoFactorStatus(
			profile.emailVerified(),
			profile.totpEnabled(),
			profile.emailOtpEnabled(),
			profile.federatedSecondFactor(),
			profile.twoFactorEnabled(),
			profile.identityVerified(),
			repository.countUnusedBackupCodes(userId),
			secretCipher.isEnabled());
	}

	/** Metodo exigido neste login, ou vazio quando a conta nao tem segundo fator. */
	public Optional<TwoFactorMethod> requiredMethod(String userId) {
		UserProfile profile = requireProfile(userId);
		if (profile.totpEnabled()) {
			return Optional.of(TwoFactorMethod.TOTP);
		}
		if (profile.emailOtpEnabled()) {
			return Optional.of(TwoFactorMethod.EMAIL);
		}
		return Optional.empty();
	}

	// ------------------------------------------------------------------ TOTP

	@Transactional
	@Override
	public TotpSetup startTotpSetup(String userId, String password) {
		requirePassword(userId, password);
		secretCipher.requireEnabled();
		UserProfile profile = requireProfile(userId);
		if (profile.totpEnabled()) {
			throw new ApiException(409, "TOTP_ALREADY_ENABLED", "O segundo fator por aplicativo ja esta ativo.");
		}
		Instant now = clock.instant();
		String secret = Totp.generateSecret();
		repository.savePendingSecret(userId, secretCipher.encrypt(secret), now);
		return new TotpSetup(secret, Totp.otpauthUri(ISSUER, profile.email(), secret));
	}

	/** Confirma o setup com um codigo do app e devolve os codigos de recuperacao (mostrados uma vez). */
	@Transactional
	@Override
	public List<String> enableTotp(String userId, String code, String password, String sessionToken) {
		requirePassword(userId, password);
		secretCipher.requireEnabled();
		String pendingEncrypted = repository.findPendingSecret(userId)
			.orElseThrow(() -> new ApiException(400, "TOTP_SETUP_NOT_STARTED",
				"Comece a configuracao do aplicativo authenticator antes de confirmar o codigo."));
		Instant now = clock.instant();
		repository.findPendingSecretCreatedAt(userId)
			.filter(createdAt -> createdAt.plus(PENDING_SECRET_TTL).isBefore(now))
			.ifPresent(createdAt -> {
				throw new ApiException(410, "TOTP_SETUP_EXPIRED",
					"A configuracao expirou. Gere um novo codigo no aplicativo.");
			});

		String secret = secretCipher.decrypt(pendingEncrypted);
		long step = Totp.matchingStep(secret, code, now, Totp.DEFAULT_WINDOW);
		if (step < 0) {
			throw new ApiException(400, "INVALID_TWO_FACTOR_CODE", "Codigo invalido. Confira o app e tente de novo.");
		}

		UserProfile profile = requireProfile(userId);
		profile.enableTotp(now);
		userProfileRepository.save(profile);
		repository.activateTotpSecret(userId, pendingEncrypted, now);
		// The enrollment code is spent: it cannot complete a login inside the same window.
		repository.markTotpStepUsed(userId, step);
		sessions.revokeOthersByUser(userId, sessionToken, now);
		return issueBackupCodes(userId, now);
	}

	@Transactional
	public void disableTotp(String userId, String password, String sessionToken) {
		UserProfile profile = requireProfile(userId);
		requirePassword(userId, password);
		profile.disableTotp();
		userProfileRepository.save(profile);
		repository.clearSecrets(userId);
		repository.deleteBackupCodes(userId);
		sessions.revokeOthersByUser(userId, sessionToken, clock.instant());
	}

	// ------------------------------------------------------------------ codigo por e-mail

	/** Envia um codigo para o e-mail e guarda o hash: o usuario confirma e o metodo fica ativo. */
	@Transactional
	@Override
	public void requestEmailOtpSetup(String userId) {
		UserProfile profile = requireProfile(userId);
		if (!profile.emailVerified()) {
			throw new ApiException(409, "EMAIL_NOT_VERIFIED",
				"Confirme seu e-mail antes de ativar o segundo fator por e-mail.");
		}
		if (profile.emailOtpEnabled()) {
			throw new ApiException(409, "EMAIL_OTP_ALREADY_ENABLED",
				"O segundo fator por e-mail ja esta ativo.");
		}
		Instant now = clock.instant();
		String code = numericCode();
		repository.saveChallenge(TwoFactorChallenge.create(newChallengeId(), userId, TwoFactorMethod.EMAIL,
			Hashes.sha256Hex(code), now.plus(CHALLENGE_TTL), now));
		if (!emailService.sendTwoFactorCode(profile.email(), profile.name(), code)) {
			throw new ApiException(503, "EMAIL_SEND_FAILED",
				"Nao foi possivel enviar o codigo por e-mail agora. Use um codigo de recuperacao ou tente de novo em alguns minutos.");
		}
	}

	@Transactional
	@Override
	public void confirmEmailOtpSetup(String userId, String code, String sessionToken) {
		if (!StringUtils.hasText(code)) {
			throw new ApiException(400, "INVALID_TWO_FACTOR_CODE", "Informe o codigo enviado por e-mail.");
		}
		Instant now = clock.instant();
		TwoFactorChallenge challenge = repository
			.findLatestChallenge(userId, TwoFactorMethod.EMAIL)
			.orElseThrow(() -> new ApiException(400, "TWO_FACTOR_CHALLENGE_NOT_FOUND",
				"Peca um novo codigo por e-mail."));
		if (challenge.isExpired(now)) {
			throw new ApiException(410, "TWO_FACTOR_CHALLENGE_EXPIRED", "O codigo expirou. Peca um novo.");
		}
		requireClaim(challenge.id(), "Este codigo ja foi usado. Peca um novo.",
			"Tentativas demais. Peca um novo codigo.");
		if (!Hashes.sha256Hex(code.trim()).equals(challenge.codeHash())) {
			throw new ApiException(400, "INVALID_TWO_FACTOR_CODE", "Codigo incorreto.");
		}

		if (!repository.consumeChallenge(challenge.id(), now)) {
			throw alreadyUsed("Este codigo ja foi usado. Peca um novo.");
		}
		UserProfile profile = requireProfile(userId);
		profile.enableEmailOtp(now);
		userProfileRepository.save(profile);
		sessions.revokeOthersByUser(userId, sessionToken, now);
	}

	@Transactional
	@Override
	public void disableEmailOtp(String userId, String password, String sessionToken) {
		UserProfile profile = requireProfile(userId);
		requirePassword(userId, password);
		profile.disableEmailOtp();
		userProfileRepository.save(profile);
		sessions.revokeOthersByUser(userId, sessionToken, clock.instant());
	}

	// ------------------------------------------------------------------ desafio do login

	/**
	 * Cria o desafio do login. No metodo por e-mail o codigo sai agora; no TOTP nada e enviado (o
	 * codigo vem do aplicativo do usuario).
	 */
	@Transactional
	public TwoFactorChallenge startChallenge(String userId, TwoFactorMethod method) {
		Instant now = clock.instant();
		repository.deleteExpiredChallenges(now);
		String code = method == TwoFactorMethod.EMAIL ? numericCode() : null;
		TwoFactorChallenge challenge = TwoFactorChallenge.create(newChallengeId(), userId, method,
			code == null ? null : Hashes.sha256Hex(code), now.plus(CHALLENGE_TTL), now);
		repository.saveChallenge(challenge);

		if (method == TwoFactorMethod.EMAIL) {
			UserProfile profile = requireProfile(userId);
			if (!emailService.sendTwoFactorCode(profile.email(), profile.name(), code)) {
				throw new ApiException(503, "EMAIL_SEND_FAILED",
				"Nao foi possivel enviar o codigo por e-mail agora. Use um codigo de recuperacao ou tente de novo em alguns minutos.");
			}
		}
		return challenge;
	}

	/** Novo codigo para o mesmo desafio (so faz sentido no metodo por e-mail). */
	@Transactional
	public void resendTwoFactorCode(String challengeId) {
		Instant now = clock.instant();
		TwoFactorChallenge challenge = repository.findChallenge(challengeId)
			.orElseThrow(() -> new ApiException(404, "TWO_FACTOR_CHALLENGE_NOT_FOUND",
				"Este login expirou. Entre com sua senha novamente."));
		if (challenge.method() != TwoFactorMethod.EMAIL) {
			throw new ApiException(400, "TWO_FACTOR_RESEND_NOT_APPLICABLE",
				"Use o codigo do seu aplicativo authenticator.");
		}
		if (challenge.isConsumed() || challenge.isExpired(now)) {
			throw new ApiException(410, "TWO_FACTOR_CHALLENGE_EXPIRED",
				"Este login expirou. Entre com sua senha novamente.");
		}
		String code = numericCode();
		if (!repository.replaceCode(challenge.id(), Hashes.sha256Hex(code), now.plus(CHALLENGE_TTL))) {
			throw new ApiException(410, "TWO_FACTOR_CHALLENGE_EXPIRED",
				"Este login expirou. Entre com sua senha novamente.");
		}
		UserProfile profile = requireProfile(challenge.userId());
		if (!emailService.sendTwoFactorCode(profile.email(), profile.name(), code)) {
			throw new ApiException(503, "EMAIL_SEND_FAILED",
					"Nao foi possivel enviar o codigo por e-mail agora. Use um codigo de recuperacao ou tente de novo em alguns minutos.");
		}
	}

	/**
	 * Confere o codigo do login e devolve o id do usuario.
	 *
	 * Aceita codigo do aplicativo (TOTP) ou um codigo de recuperacao. Erro de codigo incrementa as
	 * tentativas do mesmo desafio: sem isso, daria para testar codigos indefinidamente.
	 */
	// The caller (AuthService.verifyTwoFactor) is transactional and rolls back on the error below, so
	// the attempt is counted through claimAttempt, which commits on its own.
	public String verifyChallenge(String challengeId, String code) {
		Instant now = clock.instant();
		TwoFactorChallenge challenge = repository.findChallenge(challengeId)
			.orElseThrow(() -> new ApiException(404, "TWO_FACTOR_CHALLENGE_NOT_FOUND",
				"Este login expirou. Entre com sua senha novamente."));
		if (challenge.isConsumed()) {
			throw new ApiException(409, "TWO_FACTOR_CHALLENGE_ALREADY_USED",
				"Este login ja foi concluido. Entre novamente.");
		}
		if (challenge.isExpired(now)) {
			throw new ApiException(410, "TWO_FACTOR_CHALLENGE_EXPIRED",
				"Este login expirou. Entre com sua senha novamente.");
		}
		if (!StringUtils.hasText(code)) {
			throw new ApiException(400, "INVALID_TWO_FACTOR_CODE", "Informe o codigo do segundo fator.");
		}
		// Claimed before the code is checked, so parallel guesses cannot all read the same counter.
		requireClaim(challenge.id(), "Este login ja foi concluido. Entre novamente.",
			"Tentativas demais. Entre com sua senha novamente.");

		TwoFactorMethod method = challenge.method();
		if (method == null) {
			// Linha sem metodo (legado/corrompida) nao tem como ser validada: erro explicito, nunca um
			// NPE virando 500 e escondendo a causa de quem tentou entrar.
			LOG.warn("two_factor_challenge sem metodo: {}", challenge.id());
			throw new ApiException(409, "TWO_FACTOR_CHALLENGE_INVALID",
				"Este login nao pode ser concluido. Entre com sua senha novamente.");
		}
		boolean valid = switch (method) {
			case EMAIL -> Hashes.sha256Hex(code.trim()).equals(challenge.codeHash());
			// Cada passo de tempo vale uma vez: codigo repetido falha como codigo errado.
			case TOTP -> totpMatches(challenge.userId(), code, now);
		};

		if (!valid) {
			// Codigo de recuperacao vale como alternativa (uso unico) em qualquer metodo.
			boolean recovered = repository.consumeBackupCode(challenge.userId(), Hashes.sha256Hex(normalizeBackup(code)),
				now);
			if (!recovered) {
				throw new ApiException(400, "INVALID_TWO_FACTOR_CODE", "Codigo incorreto.");
			}
		}

		// Atomic: of two parallel requests with a valid code, only one completes the login.
		if (!repository.consumeChallenge(challenge.id(), now)) {
			throw alreadyUsed("Este login ja foi concluido. Entre novamente.");
		}
		return challenge.userId();
	}

	// ------------------------------------------------------------------ apoio

	/**
	 * Confere o código TOTP do desafio de login.
	 *
	 * <p>Segredo ilegível (chave de cifragem trocada, linha corrompida) vira erro explícito — antes
	 * a exceção do decifrador subia crua e o usuário via 500 sem saber que o problema é o segundo
	 * fator da própria conta.</p>
	 *
	 * <p>A recovery code still completes the login in that case: it is stored as a hash and does not
	 * depend on the cipher key, so it is the self-service way back in to reconfigure the factor.</p>
	 */
	private boolean totpMatches(String userId, String code, Instant now) {
		java.util.Optional<String> secret = repository.findActiveSecret(userId);
		if (secret.isEmpty()) {
			return false;
		}
		String plain;
		try {
			plain = secretCipher.decrypt(secret.get());
		}
		catch (RuntimeException failure) {
			LOG.warn("segredo TOTP ilegivel para {}: {}", userId, failure.getMessage());
			if (repository.consumeBackupCode(userId, Hashes.sha256Hex(normalizeBackup(code)), now)) {
				return true;
			}
			throw new ApiException(409, "TWO_FACTOR_SECRET_UNREADABLE",
				"O segundo fator desta conta precisa ser reconfigurado. Entre com um código de recuperação ou fale com o suporte.");
		}
		long step = Totp.matchingStep(plain, code, now, Totp.DEFAULT_WINDOW);
		return step >= 0 && repository.markTotpStepUsed(userId, step);
	}

	/** A refused claim on a challenge another request just consumed reads as "already used", not 429. */
	private void requireClaim(String challengeId, String alreadyUsedMessage, String tooManyMessage) {
		switch (repository.claimAttempt(challengeId)) {
			case CLAIMED -> { }
			case CONSUMED -> throw alreadyUsed(alreadyUsedMessage);
			case OUT_OF_ATTEMPTS -> throw new ApiException(429, "TOO_MANY_ATTEMPTS", tooManyMessage);
		}
	}

	private static ApiException alreadyUsed(String message) {
		return new ApiException(409, "TWO_FACTOR_CHALLENGE_ALREADY_USED", message);
	}

	private List<String> issueBackupCodes(String userId, Instant now) {
		List<String> codes = new ArrayList<>();
		List<String> hashes = new ArrayList<>();
		for (int index = 0; index < BACKUP_CODE_COUNT; index++) {
			String code = backupCode();
			codes.add(code);
			hashes.add(Hashes.sha256Hex(normalizeBackup(code)));
		}
		repository.saveBackupCodes(userId, hashes, now);
		return codes;
	}

	private UserProfile requireProfile(String userId) {
		return userProfileRepository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
	}

	private void requirePassword(String userId, String password) {
		StoredCredential credential = credentialRepository.findByUserId(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!credential.passwordUsable()) {
			// Conta so com login social: nao ha senha para confirmar.
			throw new ApiException(409, "PASSWORD_NOT_SET",
				"Defina uma senha (em “Trocar senha”) antes de mexer no segundo fator.");
		}
		if (!StringUtils.hasText(password) || !passwordHasher.matches(password, credential.passwordHash())) {
			throw new ApiException(401, "INVALID_CREDENTIALS", "Senha incorreta.");
		}
	}

	private String numericCode() {
		return String.format("%06d", secureRandom.nextInt(1_000_000));
	}

	/** Codigo de recuperacao: 10 caracteres em blocos, para ser anotado sem erro. */
	private String backupCode() {
		String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
		StringBuilder code = new StringBuilder();
		for (int index = 0; index < 10; index++) {
			code.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
		}
		return code.substring(0, 5) + "-" + code.substring(5);
	}

	private String normalizeBackup(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private String newChallengeId() {
		return "2fa_" + UUID.randomUUID().toString().replace("-", "");
	}

}