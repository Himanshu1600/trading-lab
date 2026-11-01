package com.tradinglab.marketdata;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WatchlistFlowTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private InstrumentSeeder seeder;

	@MockitoBean
	private AlpacaAssetsClient alpaca;

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/v1/watchlists")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/instruments").param("query", "AAPL")).andExpect(status().isUnauthorized());
	}

	@Test
	void searchRanksExactSymbolFirstAndMatchesNames() throws Exception {
		String token = newUser();
		search(token, "v").andExpect(jsonPath("$[0].symbol").value("V"));
		search(token, "berkshire").andExpect(jsonPath("$[0].symbol").value("BRK.B"));
		search(token, "%").andExpect(jsonPath("$.length()").value(0));
		search(token, " ").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void seedingAddsAndUpdatesInstruments() throws Exception {
		String token = newUser();
		given(alpaca.activeUsEquities())
			.willReturn(List.of(new AlpacaAssetsClient.Asset("ZZNEW", "", "NYSE", true)));
		seeder.seed();
		search(token, "ZZNEW").andExpect(jsonPath("$[0].name").value("ZZNEW"));

		given(alpaca.activeUsEquities())
			.willReturn(List.of(new AlpacaAssetsClient.Asset("ZZNEW", "New Name Inc.", "NYSE", true)));
		seeder.seed();
		search(token, "ZZNEW").andExpect(jsonPath("$[0].name").value("New Name Inc."));
	}

	@Test
	void watchlistLifecycle() throws Exception {
		String token = newUser();
		String id = createList(token, "Tech");

		perform(post("/api/v1/watchlists/" + id + "/items/msft"), token).andExpect(status().isOk());
		perform(post("/api/v1/watchlists/" + id + "/items/BRK.B"), token).andExpect(status().isOk());
		perform(post("/api/v1/watchlists/" + id + "/items/AAPL"), token).andExpect(status().isOk());
		perform(post("/api/v1/watchlists/" + id + "/items/AAPL"), token).andExpect(status().isOk());

		perform(get("/api/v1/watchlists"), token).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Tech"))
			.andExpect(jsonPath("$[0].items.length()").value(3))
			.andExpect(jsonPath("$[0].items[0].symbol").value("AAPL"))
			.andExpect(jsonPath("$[0].items[0].name").value("Apple Inc."));

		perform(delete("/api/v1/watchlists/" + id + "/items/AAPL"), token).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));

		perform(delete("/api/v1/watchlists/" + id), token).andExpect(status().isNoContent());
		perform(get("/api/v1/watchlists"), token).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void rejectsBadInput() throws Exception {
		String token = newUser();
		String id = createList(token, "Mine");

		perform(post("/api/v1/watchlists/" + id + "/items/NOPE123"), token).andExpect(status().isNotFound());
		perform(post("/api/v1/watchlists").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine\"}"),
				token)
			.andExpect(status().isConflict());
		perform(post("/api/v1/watchlists").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"), token)
			.andExpect(status().isBadRequest());
	}

	@Test
	void usersCannotTouchEachOthersLists() throws Exception {
		String owner = newUser();
		String stranger = newUser();
		String id = createList(owner, "Private");

		perform(get("/api/v1/watchlists"), stranger).andExpect(jsonPath("$.length()").value(0));
		perform(post("/api/v1/watchlists/" + id + "/items/AAPL"), stranger).andExpect(status().isNotFound());
		perform(delete("/api/v1/watchlists/" + id + "/items/AAPL"), stranger).andExpect(status().isNotFound());
		perform(delete("/api/v1/watchlists/" + id), stranger).andExpect(status().isNotFound());
		perform(get("/api/v1/watchlists"), owner).andExpect(jsonPath("$.length()").value(1));
	}

	private ResultActions search(String token, String query) throws Exception {
		return perform(get("/api/v1/instruments").param("query", query), token).andExpect(status().isOk());
	}

	private String createList(String token, String name) throws Exception {
		String body = perform(post("/api/v1/watchlists").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\"}".formatted(name)), token)
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions perform(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token)
			throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private String newUser() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		String body = mvc
			.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Test User\",\"email\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(email)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}
