package com.tradinglab.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private UUID familyId;

	@Column(nullable = false, unique = true)
	private String tokenHash;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant revokedAt;

	protected RefreshToken() {
	}

	RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	UUID getId() {
		return id;
	}

	UUID getUserId() {
		return userId;
	}

	UUID getFamilyId() {
		return familyId;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	Instant getRevokedAt() {
		return revokedAt;
	}

}
