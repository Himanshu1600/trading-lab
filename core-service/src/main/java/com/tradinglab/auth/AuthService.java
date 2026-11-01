package com.tradinglab.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

	static final String JWT_ISSUER = "trading-lab";

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
	private final AuthProperties properties;
	private final SecureRandom random = new SecureRandom();
	// Checked when the email is unknown, so login takes the same time either way.
	private final String dummyHash;

	AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
			JwtEncoder jwtEncoder, AuthProperties properties) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	@Transactional
	public Session register(String name, String email, String password) {
		User user;
		try {
			user = users.saveAndFlush(new User(name.trim(), normalize(email), passwordEncoder.encode(password)));
		}
		catch (DataIntegrityViolationException ex) {
			throw new AuthException(HttpStatus.CONFLICT, "An account with this email already exists");
		}
		return startSession(user, UUID.randomUUID());
	}

	@Transactional
	public Session login(String email, String password) {
		User user = users.findByEmail(normalize(email)).orElse(null);
		String hash = (user != null) ? user.getPasswordHash() : dummyHash;
		if (!passwordEncoder.matches(password, hash) || user == null) {
			throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
		}
		return startSession(user, UUID.randomUUID());
	}

	// noRollbackFor keeps the family revocation when a replayed token is rejected.
	@Transactional(noRollbackFor = AuthException.class)
	public Session refresh(String rawToken) {
		RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken)).orElseThrow(AuthService::invalidSession);
		Instant now = Instant.now();
		if (token.getExpiresAt().isBefore(now)) {
			throw invalidSession();
		}
		if (refreshTokens.revoke(token.getId(), now) == 0) {
			Instant revokedAt = token.getRevokedAt();
			if (revokedAt != null && revokedAt.plus(properties.refreshReuseGrace()).isBefore(now)) {
				refreshTokens.revokeFamily(token.getFamilyId(), now);
			}
			throw invalidSession();
		}
		User user = users.findById(token.getUserId()).orElseThrow(AuthService::invalidSession);
		return startSession(user, token.getFamilyId());
	}

	@Transactional
	public void logout(String rawToken) {
		refreshTokens.findByTokenHash(sha256(rawToken))
			.ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), Instant.now()));
	}

	@Transactional(readOnly = true)
	public User currentUser(UUID id) {
		return users.findById(id).orElseThrow(AuthService::invalidSession);
	}

	private Session startSession(User user, UUID familyId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		refreshTokens.save(new RefreshToken(user.getId(), familyId, sha256(rawToken),
				Instant.now().plus(properties.refreshTokenTtl())));
		return new Session(user, accessToken(user), rawToken);
	}

	private String accessToken(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(JWT_ISSUER)
			.subject(user.getId().toString())
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	private static String normalize(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static AuthException invalidSession() {
		return new AuthException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
	}

	record Session(User user, String accessToken, String refreshToken) {
	}

}
