package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1")
class OrderController {

	private static final int MAX_KEY_LENGTH = 100;

	private final OrderService orderService;

	OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping("/orders")
	ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody PlaceOrderRequest request) {
		if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
		}
		UUID userId = userId(jwt);
		PaperOrder order;
		try {
			order = orderService.place(userId, idempotencyKey, request);
		}
		catch (DataIntegrityViolationException ex) {
			order = orderService.findPlaced(userId, idempotencyKey);
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
	}

	@GetMapping("/orders")
	List<OrderResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) PaperOrder.Status status) {
		return orderService.list(userId(jwt), status).stream().map(OrderResponse::from).toList();
	}

	@DeleteMapping("/orders/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		orderService.cancel(userId(jwt), id);
	}

	@GetMapping("/fills")
	List<FillResponse> fills(@AuthenticationPrincipal Jwt jwt) {
		return orderService.fills(userId(jwt)).stream().map(FillResponse::from).toList();
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record PlaceOrderRequest(@NotBlank String symbol, @NotNull PaperOrder.Side side, @NotNull PaperOrder.Type type,
			@Min(1) @Max(1_000_000) long qty,
			@DecimalMin(value = "0.0001") @Digits(integer = 10, fraction = 4) BigDecimal limitPrice) {

		@AssertTrue(message = "limit orders need a limit price, and market orders can't have one")
		boolean isLimitPriceConsistent() {
			return (type == PaperOrder.Type.LIMIT) == (limitPrice != null);
		}

	}

	record OrderResponse(UUID id, String symbol, PaperOrder.Side side, PaperOrder.Type type, long qty,
			BigDecimal limitPrice, PaperOrder.Status status, BigDecimal avgFillPrice, String rejectReason,
			Instant createdAt, Instant updatedAt) {

		static OrderResponse from(PaperOrder order) {
			return new OrderResponse(order.getId(), order.getSymbol(), order.getSide(), order.getType(), order.getQty(),
					order.getLimitPrice(), order.getStatus(), order.getAvgFillPrice(), order.getRejectReason(),
					order.getCreatedAt(), order.getUpdatedAt());
		}

	}

	record FillResponse(UUID orderId, String symbol, PaperOrder.Side side, long qty, BigDecimal price,
			Instant filledAt) {

		static FillResponse from(Fill fill) {
			return new FillResponse(fill.getOrderId(), fill.getSymbol(), fill.getSide(), fill.getQty(), fill.getPrice(),
					fill.getFilledAt());
		}

	}

}
