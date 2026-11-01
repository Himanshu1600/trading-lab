package com.tradinglab.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
class AuthController {

	private static final String REFRESH_COOKIE = "refresh_token";
	private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	private final AuthService authService;
	private final AuthProperties properties;

	AuthController(AuthService authService, AuthProperties properties) {
		this.authService = authService;
		this.properties = properties;
	}

	@PostMapping("/auth/register")
	ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
		return respond(HttpStatus.CREATED, authService.register(request.name(), request.email(), request.password()));
	}

	@PostMapping("/auth/login")
	ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return respond(HttpStatus.OK, authService.login(request.email(), request.password()));
	}

	@PostMapping("/auth/refresh")
	ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		if (refreshToken == null) {
			throw new AuthException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
		}
		return respond(HttpStatus.OK, authService.refresh(refreshToken));
	}

	@PostMapping("/auth/logout")
	ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		if (refreshToken != null) {
			authService.logout(refreshToken);
		}
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
			.build();
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return UserResponse.from(authService.currentUser(UUID.fromString(jwt.getSubject())));
	}

	private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthService.Session session) {
		ResponseCookie cookie = refreshCookie(session.refreshToken(), properties.refreshTokenTtl());
		AuthResponse body = new AuthResponse(session.accessToken(), properties.accessTokenTtl().toSeconds(),
				UserResponse.from(session.user()));
		return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
	}

	private ResponseCookie refreshCookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
			.httpOnly(true)
			.secure(properties.cookieSecure())
			.sameSite("Strict")
			.path(REFRESH_COOKIE_PATH)
			.maxAge(maxAge)
			.build();
	}

	record RegisterRequest(@NotBlank @Size(max = 50) String name, @NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8) String password) {

		// BCrypt only reads the first 72 bytes, and its encoder rejects anything longer.
		@AssertTrue(message = "must be at most 72 bytes")
		boolean isPasswordWithinLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}

	}

	record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	record UserResponse(UUID id, String name, String email) {

		static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getName(), user.getEmail());
		}

	}

	record AuthResponse(String accessToken, long expiresIn, UserResponse user) {
	}

}
