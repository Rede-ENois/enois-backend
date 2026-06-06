package com.socialcoisa.rede.identity.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

@Component
class PasswordHasher {

	private static final int ITERATIONS = 120000;
	private static final int KEY_LENGTH = 256;
	private final SecureRandom secureRandom = new SecureRandom();

	String hash(String password) {
		byte[] salt = new byte[16];
		secureRandom.nextBytes(salt);
		byte[] encoded = pbkdf2(password, salt);
		return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
				+ Base64.getEncoder().encodeToString(encoded);
	}

	boolean matches(String password, String storedHash) {
		String[] parts = storedHash.split(":");
		if (parts.length != 3) {
			return false;
		}
		byte[] salt = Base64.getDecoder().decode(parts[1]);
		byte[] expected = Base64.getDecoder().decode(parts[2]);
		byte[] actual = pbkdf2(password, salt, Integer.parseInt(parts[0]));
		return MessageDigest.isEqual(expected, actual);
	}

	private byte[] pbkdf2(String password, byte[] salt) {
		return pbkdf2(password, salt, ITERATIONS);
	}

	private byte[] pbkdf2(String password, byte[] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		}
		catch (Exception exception) {
			throw new ApiException(500, "PASSWORD_HASH_FAILURE", "Nao foi possivel processar credenciais.");
		}
	}
}
