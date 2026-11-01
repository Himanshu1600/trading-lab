package com.tradinglab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "auth.refresh-reuse-grace=0s")
@AutoConfigureMockMvc
class AuthFlowTests {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	private MockMvc mvc;

	@Test
	void registerReturnsTokenThatOpensMe() throws Exception {
		String email = newEmail();
		MvcResult result = register(email).andExpect(status().isCreated())
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn();

		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(result)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.name").value("Test User"));
	}

	@Test
	void meRejectsMissingAndForgedTokens() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateEmailIsRejectedIgnoringCase() throws Exception {
		String email = newEmail();
		register(email).andExpect(status().isCreated());
		register(email.toUpperCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("An account with this email already exists"));
	}

	@Test
	void invalidRegistrationsAreRejected() throws Exception {
		postJson("/api/v1/auth/register", newEmail(), "short").andExpect(status().isBadRequest());
		postJson("/api/v1/auth/register", "not-an-email", PASSWORD).andExpect(status().isBadRequest());
		postJson("/api/v1/auth/register", newEmail(), "é".repeat(40)).andExpect(status().isBadRequest());
		for (String name : new String[] { "   ", "x".repeat(51) }) {
			String body = "{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(name, newEmail(), PASSWORD);
			mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		}
	}

	@Test
	void nameIsTrimmedAndReturnedOnLogin() throws Exception {
		String email = newEmail();
		String body = "{\"name\":\"  Hunny  \",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.name").value("Hunny"));
		postJson("/api/v1/auth/login", email, PASSWORD).andExpect(jsonPath("$.user.name").value("Hunny"));
	}

	@Test
	void loginChecksPassword() throws Exception {
		String email = newEmail();
		register(email).andExpect(status().isCreated());

		postJson("/api/v1/auth/login", email, PASSWORD).andExpect(status().isOk());
		postJson("/api/v1/auth/login", email, "wrong-password").andExpect(status().isUnauthorized());
		postJson("/api/v1/auth/login", email, "é".repeat(100)).andExpect(status().isUnauthorized());
		postJson("/api/v1/auth/login", newEmail(), PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshRotatesTokenAndReplayRevokesTheFamily() throws Exception {
		Cookie first = refreshCookie(register(newEmail()).andReturn());

		MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isOk()).andReturn();
		Cookie second = refreshCookie(rotated);
		assertThat(second.getValue()).isNotEqualTo(first.getValue());
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(rotated))).andExpect(status().isOk());

		Thread.sleep(5);
		mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/auth/refresh").cookie(second)).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
		Cookie cookie = refreshCookie(register(newEmail()).andReturn());

		mvc.perform(post("/api/v1/auth/logout").cookie(cookie))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
		mvc.perform(post("/api/v1/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshWithoutCookieIsUnauthorized() throws Exception {
		mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
	}

	@Test
	void corsAllowsOnlyTheWebOrigin() throws Exception {
		mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
		mvc.perform(options("/api/v1/auth/login").header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(status().isForbidden());
	}

	private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
		return postJson("/api/v1/auth/register", email, PASSWORD);
	}

	private org.springframework.test.web.servlet.ResultActions postJson(String path, String email, String password)
			throws Exception {
		String body = "{\"name\":\"Test User\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String newEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String bearer(MvcResult result) throws Exception {
		String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		return "Bearer " + accessToken;
	}

	private static Cookie refreshCookie(MvcResult result) {
		return result.getResponse().getCookie("refresh_token");
	}

}
