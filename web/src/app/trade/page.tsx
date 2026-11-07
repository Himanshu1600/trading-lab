"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import {
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";

import { MarketStatusBadge } from "@/components/market-status";
import { PriceChart } from "@/components/price-chart";
import { SymbolSearch } from "@/components/symbol-search";
import { apiFetch, useRequireAuth } from "@/lib/auth";
import {
  changeClass,
  dayChangePercent,
  formatMoney,
  useMarketStatus,
  useQuotes,
} from "@/lib/market";

const LAST_SYMBOL_KEY = "trade:last-symbol";

function readLastSymbol(): string {
  try {
    return localStorage.getItem(LAST_SYMBOL_KEY) ?? "";
  } catch {
    return "";
  }
}

type Side = "BUY" | "SELL";
type OrderType = "MARKET" | "LIMIT";
type Position = { symbol: string; availableQty: number };

export default function TradePage() {
  return (
    <Suspense>
      <TradeRoute />
    </Suspense>
  );
}

/**
 * The address decides the stock. Next.js keeps visited pages alive and restores them with
 * their old state (cacheComponents), so keying the ticket on the address makes a new address
 * start a fresh ticket instead of showing whichever stock was there before.
 */
function TradeRoute() {
  const params = useSearchParams();
  // This only renders in the browser (useSearchParams inside Suspense), so reading
  // localStorage here can't cause a server/browser mismatch.
  const symbol = params.get("symbol")?.toUpperCase() || readLastSymbol();
  const side: Side = params.get("side") === "SELL" ? "SELL" : "BUY";
  return <TradeTicket key={params.toString()} symbol={symbol} initialSide={side} />;
}

function TradeTicket({ symbol, initialSide }: { symbol: string; initialSide: Side }) {
  const user = useRequireAuth();
  const router = useRouter();
  const [instrument, setInstrument] = useState<{ symbol: string; name: string } | null>(null);
  const [side, setSide] = useState<Side>(initialSide);
  const [type, setType] = useState<OrderType>("MARKET");
  const [qty, setQty] = useState("1");
  const [limitPrice, setLimitPrice] = useState("");
  const [buyingPower, setBuyingPower] = useState<number | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [placed, setPlaced] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Kept across a failed network call, so a retry can't place the order twice.
  const idempotencyKey = useRef<string | null>(null);

  const quotes = useQuotes(symbol ? [symbol] : []);
  const market = useMarketStatus();
  const quote = quotes[symbol];

  const refreshAccount = useCallback(() => {
    apiFetch("/api/v1/account")
      .then((response) => (response.ok ? response.json() : null))
      .then((account) => setBuyingPower(account?.buyingPower ?? null))
      .catch(() => setBuyingPower(null));
    apiFetch("/api/v1/positions")
      .then((response) => (response.ok ? response.json() : []))
      .then(setPositions)
      .catch(() => setPositions([]));
  }, []);

  useEffect(() => {
    if (user) refreshAccount();
  }, [user, refreshAccount]);

  useEffect(() => {
    if (!symbol) return;
    try {
      localStorage.setItem(LAST_SYMBOL_KEY, symbol);
    } catch {
      // Private browsing can block storage. Remembering the stock is only a convenience.
    }
  }, [symbol]);

  // Looks up the company name, so it shows however you arrived: search, watchlist or link.
  useEffect(() => {
    if (!user || !symbol) return;
    let active = true;
    apiFetch(`/api/v1/instruments?query=${encodeURIComponent(symbol)}`)
      .then((response) => (response.ok ? response.json() : []))
      .catch(() => [])
      .then((found: { symbol: string; name: string }[]) => {
        const match = found.find((item) => item.symbol === symbol);
        if (active && match) setInstrument(match);
      });
    return () => {
      active = false;
    };
  }, [user, symbol]);

  const companyName = instrument?.symbol === symbol ? instrument.name : "";

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setPlaced(null);
    setSubmitting(true);
    idempotencyKey.current ??= crypto.randomUUID();
    try {
      const response = await apiFetch("/api/v1/orders", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey.current,
        },
        body: JSON.stringify({
          symbol,
          side,
          type,
          qty: Number(qty),
          ...(type === "LIMIT" && { limitPrice: Number(limitPrice) }),
        }),
      });
      idempotencyKey.current = null;
      if (response.ok) {
        const action = side === "BUY" ? "Buy" : "Sell";
        setPlaced(`${action} order placed for ${qty} ${symbol}.`);
        refreshAccount();
      } else {
        const problem = await response.json().catch(() => null);
        setError(
          response.status === 400
            ? "Check the quantity and price."
            : (problem?.detail ?? "The order didn't go through. Try again."),
        );
      }
    } catch {
      setError("Can't reach the server. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  if (!user) {
    return null;
  }

  const shares = Number(qty) || 0;
  const unitPrice =
    type === "LIMIT"
      ? Number(limitPrice) || 0
      : ((side === "BUY" ? quote?.buyPrice : quote?.sellPrice) ?? 0);
  const estimate = shares * unitPrice;
  const available =
    positions.find((position) => position.symbol === symbol)?.availableQty ?? 0;
  const change = quote ? dayChangePercent(quote) : null;

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Trade</h1>
          <p className="mt-1 text-sm text-muted">
            Buy and sell with paper money.
          </p>
        </div>
        <MarketStatusBadge />
      </div>

      <div className="grid items-start gap-6 md:grid-cols-[1fr_20rem]">
        <section className="card flex flex-col gap-4 p-4">
          <SymbolSearch
            actionLabel="Select"
            onSelect={(selected) => {
              // Changing the address starts a fresh ticket for the new stock (see TradeRoute).
              router.replace(`/trade?symbol=${encodeURIComponent(selected.symbol)}`, {
                scroll: false,
              });
            }}
          />

          {!symbol && (
            <p className="py-8 text-center text-sm text-muted">
              Search for a stock to start.
            </p>
          )}

          {symbol && (
            <div>
              <p className="font-mono text-sm font-semibold">{symbol}</p>
              {companyName && (
                <p className="truncate text-sm text-muted">{companyName}</p>
              )}
              {quote ? (
                <>
                  <p className="mt-2 text-3xl font-semibold tracking-tight">
                    {formatMoney(quote.last)}
                  </p>
                  {change !== null && (
                    <p className={`text-sm font-medium ${changeClass(change)}`}>
                      {change > 0 ? "+" : ""}
                      {change.toFixed(2)}% today
                    </p>
                  )}
                  <dl className="mt-3 flex gap-6 text-sm">
                    <div>
                      <dt className="text-muted">Bid</dt>
                      <dd>{quote.bid ? formatMoney(quote.bid) : "—"}</dd>
                    </div>
                    <div>
                      <dt className="text-muted">Ask</dt>
                      <dd>{quote.ask ? formatMoney(quote.ask) : "—"}</dd>
                    </div>
                  </dl>
                </>
              ) : (
                <p className="mt-2 text-sm text-muted">No price available yet.</p>
              )}
            </div>
          )}
        </section>

        <form onSubmit={submit} className="card flex flex-col gap-4 p-4">
          <div
            role="group"
            aria-label="Side"
            className="grid grid-cols-2 gap-1 rounded-lg bg-background p-1"
          >
            {(["BUY", "SELL"] as const).map((option) => (
              <button
                key={option}
                type="button"
                aria-pressed={side === option}
                onClick={() => setSide(option)}
                className="btn btn-sm btn-ghost aria-pressed:bg-surface aria-pressed:text-foreground aria-pressed:shadow-sm"
              >
                {option === "BUY" ? "Buy" : "Sell"}
              </button>
            ))}
          </div>

          <label className="flex flex-col gap-1.5 text-sm font-medium">
            Order type
            <select
              value={type}
              onChange={(event) => setType(event.target.value as OrderType)}
              className="input font-normal"
            >
              <option value="MARKET">Market</option>
              <option value="LIMIT">Limit</option>
            </select>
          </label>

          <label className="flex flex-col gap-1.5 text-sm font-medium">
            Shares
            <input
              type="number"
              required
              min={1}
              max={1000000}
              step={1}
              value={qty}
              onChange={(event) => setQty(event.target.value)}
              className="input font-normal"
            />
          </label>

          {type === "LIMIT" && (
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              Limit price
              <input
                type="number"
                required
                min={0.01}
                step={0.01}
                value={limitPrice}
                onChange={(event) => setLimitPrice(event.target.value)}
                className="input font-normal"
              />
            </label>
          )}

          <dl className="flex flex-col gap-1 text-sm">
            <div className="flex justify-between">
              <dt className="text-muted">Estimated {side === "BUY" ? "cost" : "value"}</dt>
              <dd>{formatMoney(estimate)}</dd>
            </div>
            {side === "BUY" ? (
              <div className="flex justify-between">
                <dt className="text-muted">Buying power</dt>
                <dd>{buyingPower !== null ? formatMoney(buyingPower) : "—"}</dd>
              </div>
            ) : (
              <div className="flex justify-between">
                <dt className="text-muted">Shares you can sell</dt>
                <dd>{available}</dd>
              </div>
            )}
          </dl>

          {error && (
            <p role="alert" className="alert">
              {error}
            </p>
          )}
          {placed && (
            <p className="rounded-lg bg-accent-soft px-3 py-2 text-sm text-accent">
              {placed}{" "}
              {market && !market.open && "It will wait for the market to open. "}
              <Link href="/portfolio" className="font-medium underline">
                View orders
              </Link>
            </p>
          )}

          <button
            type="submit"
            disabled={!symbol || submitting}
            className="btn btn-primary"
          >
            {submitting
              ? "Placing…"
              : `${side === "BUY" ? "Buy" : "Sell"} ${symbol}`.trim()}
          </button>
        </form>
      </div>

      {symbol ? (
        <section aria-label={`${symbol} price chart`} className="card p-4">
          <PriceChart key={symbol} symbol={symbol} />
        </section>
      ) : (
        <section
          aria-label="Price chart"
          className="flex h-64 items-center justify-center rounded-xl border border-dashed border-border px-4 text-center text-sm text-muted"
        >
          Search for a stock above to see its price chart.
        </section>
      )}
    </main>
  );
}
