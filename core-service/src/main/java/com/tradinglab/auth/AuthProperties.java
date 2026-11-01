package com.tradinglab.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Validated
@ConfigurationProperties("auth")
record AuthProperties(
		@NotBlank(message = "set JWT_SIGNING_KEY in .env") @Size(min = 32, message = "JWT_SIGNING_KEY must be at least 32 characters") String jwtSigningKey,
		@NotNull Duration accessTokenTtl,
		@NotNull Duration refreshTokenTtl,
		@NotNull Duration refreshReuseGrace,
		boolean cookieSecure,
		@NotBlank String webOrigin) {
}
