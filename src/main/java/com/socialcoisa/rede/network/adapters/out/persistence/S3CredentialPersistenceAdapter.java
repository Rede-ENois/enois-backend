package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.S3CredentialRepository;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.shared.security.SecretCipher;

/**
 * Credenciais do gateway S3 em {@code network_storage_s3_credentials} (V357).
 *
 * <p>O segredo é <b>decifrado por {@link SecretCipher}</b> (o mesmo AES-GCM do TOTP). Sem a chave de
 * cifra, {@link #secretFor(String)} devolve {@code null} e o gateway recusa a assinatura com
 * {@code SignatureDoesNotMatch} — em vez de aceitar requisição sem conferir.</p>
 */
@Repository
class S3CredentialPersistenceAdapter implements S3CredentialRepository {

	private static final String COLUMNS = """
		id, access_key, owner_user_id, allowed_bucket, allowed_operations, secret_encrypted, revoked_at, created_at
		""";

	private static final String INSERT = """
		insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
			allowed_bucket, allowed_operations, created_at)
		values (?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String SELECT_BY_ACCESS_KEY = "select " + COLUMNS
		+ " from network_storage_s3_credentials where access_key = ?";

	private static final String SELECT_BY_OWNER = "select " + COLUMNS
		+ " from network_storage_s3_credentials where owner_user_id = ? order by created_at desc limit ?";

	private static final String SELECT_SECRET = "select secret_encrypted from network_storage_s3_credentials where id = ?";

	private static final String REVOKE = """
		update network_storage_s3_credentials set revoked_at = ?
		where id = ? and revoked_at is null
		""";

	private static final RowMapper<S3CredentialRow> MAPPER = (ResultSet rs, int rowNum) -> new S3CredentialRow(
		new S3Credential(rs.getString("id"), rs.getString("access_key"), rs.getString("owner_user_id"),
			rs.getString("allowed_bucket"), rs.getString("allowed_operations"), instant(rs, "revoked_at"),
			instant(rs, "created_at")),
		rs.getString("secret_encrypted"));

	private final JdbcTemplate jdbc;
	private final SecretCipher cipher;

	S3CredentialPersistenceAdapter(JdbcTemplate jdbc, SecretCipher cipher) {
		this.jdbc = jdbc;
		this.cipher = cipher;
	}

	@Override
	public Optional<S3Credential> findByAccessKey(String accessKey) {
		return jdbc.query(SELECT_BY_ACCESS_KEY, MAPPER, accessKey).stream().findFirst().map(S3CredentialRow::credential);
	}

	@Override
	public String secretFor(String credentialId) {
		String encrypted = jdbc.query(SELECT_SECRET, (rs, row) -> rs.getString(1), credentialId).stream().findFirst()
			.orElse(null);
		if (encrypted == null || !cipher.isEnabled()) {
			return null;
		}
		return cipher.decrypt(encrypted);
	}

	@Override
	public List<S3Credential> findByOwner(String ownerUserId, int limit) {
		return jdbc.query(SELECT_BY_OWNER, MAPPER, ownerUserId, limit).stream().map(S3CredentialRow::credential).toList();
	}

	@Override
	public S3Credential save(S3Credential credential, String secretPlain) {
		// O claro entra aqui e sai cifrado; o hash é o que sobra conferível. `requireEnabled` antes:
		// uma credencial que não se pode decifrar seria recusada na primeira requisição.
		cipher.requireEnabled();
		String hash = com.socialcoisa.rede.shared.security.Hashes.sha256Hex(secretPlain);
		String encrypted = cipher.encrypt(secretPlain);
		jdbc.update(INSERT, credential.id(), credential.accessKey(), hash, encrypted,
			credential.ownerUserId(), credential.allowedBucket(), credential.allowedOperations(),
			Timestamp.from(credential.createdAt()));
		return credential;
	}

	@Override
	public boolean revoke(String id, Instant now) {
		return jdbc.update(REVOKE, Timestamp.from(now), id) == 1;
	}

	private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private record S3CredentialRow(S3Credential credential, String secretEncrypted) {
	}
}