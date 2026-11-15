package com.tradinglab.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws}, with Spring's in-memory broker.
 *
 * <ul>
 * <li>{@code /topic/quotes.<SYMBOL>}: latest quote, sent when it changes</li>
 * <li>{@code /user/queue/orders}: the signed-in user's order events</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final StompAuthInterceptor auth;
	private final String webOrigin;

	WebSocketConfig(StompAuthInterceptor auth, @Value("${auth.web-origin}") String webOrigin) {
		this.auth = auth;
		this.webOrigin = webOrigin;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOrigins(webOrigin);
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(auth);
	}

}
