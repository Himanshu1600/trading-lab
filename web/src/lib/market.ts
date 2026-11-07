"use client";

import { useEffect, useState } from "react";

import { apiFetch } from "@/lib/auth";

export type Instrument = { symbol: string; name: string; exchange: string };

export type Quote = {
  symbol: string;
  bid: number;
  ask: number;
  last: number;
  previousClose: number;
  buyPrice: number;
  sellPrice: number;
};

export type MarketStatus = {
  open: boolean;
  nextOpen: string | null;
  nextClose: string | null;
};

const QUOTE_REFRESH_MS = 5_000;
const CLOCK_REFRESH_MS = 30_000;

const usd = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
});

export function formatMoney(value: number): string {
  return usd.format(value);
}

/** Like formatMoney, with an explicit sign, for profit and loss. */
export function formatChange(value: number): string {
  return `${value > 0 ? "+" : ""}${usd.format(value)}`;
}

export function changeClass(value: number): string {
  if (value === 0) return "text-muted";
  return value > 0 ? "text-accent" : "text-danger";
}

/** Percent move since the previous close, or null if that close is unknown. */
export function dayChangePercent(quote: Quote): number | null {
  if (!quote.previousClose) return null;
  return ((quote.last - quote.previousClose) / quote.previousClose) * 100;
}

export function useQuotes(symbols: string[]): Record<string, Quote> {
  const [quotes, setQuotes] = useState<Record<string, Quote>>({});
  const key = [...symbols].sort().join(",");

  useEffect(() => {
    if (!key) return;
    let active = true;
    function load() {
      apiFetch(`/api/v1/market/quotes?symbols=${encodeURIComponent(key)}`)
        .then((response) => (response.ok ? response.json() : []))
        .catch(() => [])
        .then((found: Quote[]) => {
          if (active) {
            setQuotes(Object.fromEntries(found.map((q) => [q.symbol, q])));
          }
        });
    }
    load();
    const timer = setInterval(load, QUOTE_REFRESH_MS);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [key]);

  return quotes;
}

export function useMarketStatus(): MarketStatus | null {
  const [status, setStatus] = useState<MarketStatus | null>(null);

  useEffect(() => {
    let active = true;
    function load() {
      apiFetch("/api/v1/market/clock")
        .then((response) => (response.ok ? response.json() : null))
        .catch(() => null)
        .then((found: MarketStatus | null) => {
          if (active) setStatus(found);
        });
    }
    load();
    const timer = setInterval(load, CLOCK_REFRESH_MS);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, []);

  return status;
}
