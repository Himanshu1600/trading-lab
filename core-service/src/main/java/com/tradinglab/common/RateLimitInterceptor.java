package com.tradinglab.common;

import java.security.Principal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits the requests that cost something: password guesses, orders, backtests and AI calls.
 * Reads are never limited. Over the limit, the answer is 429 with a {@code Retry-After} header.
 *
 * <p>It runs after the security filters, so the user is known. Login and sign-up have no user yet
 * and are counted per IP address. Behind a proxy, set {@code server.forward-headers-strategy} so
 * that address is the visitor's and not the proxy's.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	private final RateLimiter limiter;
	private final RateLimitProperties properties;

	RateLimitInterceptor(RateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/v1/**");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"POST".equals(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI();
		Principal user = request.getUserPrincipal();
		if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register")) {
			check(response, "auth:" + request.getRemoteAddr(), properties.auth(), "sign-in attempts");
		}
		else if (user == null) {
			return true;
		}
		else if (path.equals("/api/v1/orders")) {
			check(response, "orders:" + user.getName(), properties.orders(), "orders");
		}
		else if (path.equals("/api/v1/backtests")) {
			check(response, "backtests:" + user.getName(), properties.backtests(), "backtests");
		}
		else if (path.startsWith("/api/v1/ai/") || path.endsWith("/explain") || path.equals("/api/v1/journal/insights")) {
			check(response, "ai:" + user.getName(), properties.ai(), "AI requests");
		}
		return true;
	}

	private void check(HttpServletResponse response, String key, int limit, String what) {
		long wait = limiter.secondsUntilAllowed(key, limit, properties.window());
		if (wait > 0) {
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(wait));
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many " + what + ". Try again in " + wait + " seconds.");
		}
	}

}
