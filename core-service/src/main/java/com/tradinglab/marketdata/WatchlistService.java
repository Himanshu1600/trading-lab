package com.tradinglab.marketdata;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.marketdata.InstrumentController.InstrumentResponse;

@Service
class WatchlistService {

	private final WatchlistRepository watchlists;
	private final InstrumentRepository instruments;

	WatchlistService(WatchlistRepository watchlists, InstrumentRepository instruments) {
		this.watchlists = watchlists;
		this.instruments = instruments;
	}

	@Transactional(readOnly = true)
	public List<WatchlistResponse> list(UUID userId) {
		List<Watchlist> lists = watchlists.findByUserIdOrderByCreatedAt(userId);
		Map<String, Instrument> bySymbol = loadInstruments(
				lists.stream().flatMap(list -> list.getSymbols().stream()).collect(Collectors.toSet()));
		return lists.stream().map(list -> toResponse(list, bySymbol)).toList();
	}

	@Transactional
	public WatchlistResponse create(UUID userId, String name) {
		try {
			Watchlist list = watchlists.saveAndFlush(new Watchlist(userId, name.trim()));
			return toResponse(list, Map.of());
		}
		catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a watchlist with this name");
		}
	}

	@Transactional
	public void delete(UUID userId, UUID id) {
		watchlists.delete(find(userId, id));
	}

	@Transactional
	public WatchlistResponse addSymbol(UUID userId, UUID id, String symbol) {
		Watchlist list = find(userId, id);
		Instrument instrument = instruments.findById(symbol.toUpperCase(Locale.ROOT))
			.filter(Instrument::isTradable)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol"));
		list.getSymbols().add(instrument.getSymbol());
		return toResponse(list, loadInstruments(list.getSymbols()));
	}

	@Transactional
	public WatchlistResponse removeSymbol(UUID userId, UUID id, String symbol) {
		Watchlist list = find(userId, id);
		list.getSymbols().remove(symbol.toUpperCase(Locale.ROOT));
		return toResponse(list, loadInstruments(list.getSymbols()));
	}

	private Watchlist find(UUID userId, UUID id) {
		return watchlists.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist not found"));
	}

	private Map<String, Instrument> loadInstruments(Collection<String> symbols) {
		return instruments.findAllById(symbols)
			.stream()
			.collect(Collectors.toMap(Instrument::getSymbol, Function.identity()));
	}

	private static WatchlistResponse toResponse(Watchlist list, Map<String, Instrument> bySymbol) {
		List<InstrumentResponse> items = list.getSymbols()
			.stream()
			.map(bySymbol::get)
			.sorted(Comparator.comparing(Instrument::getSymbol))
			.map(InstrumentResponse::from)
			.toList();
		return new WatchlistResponse(list.getId(), list.getName(), items);
	}

	record WatchlistResponse(UUID id, String name, List<InstrumentResponse> items) {
	}

}
