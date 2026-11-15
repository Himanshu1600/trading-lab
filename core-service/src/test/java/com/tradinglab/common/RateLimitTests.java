package com.tradinglab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tradinglab.TestcontainersConfiguration;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "rate-limit.auth=3", "rate-limit.window=1h" })
@AutoConfigureMockMvc
class RateLimitTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RateLimiter limiter;

	@Test
	void passwordGuessingFromOneAddressIsCutOff() throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			mvc.perform(loginFrom("203.0.113.7")).andExpect(status().isUnauthorized());
		}
		mvc.perform(loginFrom("203.0.113.7"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.detail").exists());

		// Another address is unaffected.
		mvc.perform(loginFrom("203.0.113.8")).andExpect(status().isUnauthorized());
	}

	@Test
	void readsAreNeverLimited() throws Exception {
		for (int i = 0; i < 6; i++) {
			mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void theLimiterCountsPerKeyAndReportsTheWait() {
		String key = "test:" + UUID.randomUUID();
		Duration window = Duration.ofMinutes(10);
		assertThat(limiter.secondsUntilAllowed(key, 2, window)).isZero();
		assertThat(limiter.secondsUntilAllowed(key, 2, window)).isZero();
		assertThat(limiter.secondsUntilAllowed(key, 2, window)).isBetween(1L, 600L);
		assertThat(limiter.secondsUntilAllowed(key + "-other", 2, window)).isZero();
	}

	private static MockHttpServletRequestBuilder loginFrom(String address) {
		return post("/api/v1/auth/login").with(request -> {
			request.setRemoteAddr(address);
			return request;
		})
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-1\"}");
	}

}
