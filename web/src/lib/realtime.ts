"use client";

import { Client, type StompSubscription } from "@stomp/stompjs";
import { useEffect, useRef, useSyncExternalStore } from "react";

import { apiUrl, getAccessToken } from "@/lib/auth";

/** What core-service sends on /user/queue/orders after each order change commits. */
export type OrderEvent = {
  kind: "PLACED" | "TRIGGERED" | "FILLED" | "REJECTED" | "CANCELLED" | "EXPIRED";
  orderId: string;
  symbol: string;
  side: "BUY" | "SELL";
  type: string;
  qty: number;
  status: string;
  fillPrice: number | null;
  reason: string | null;
  at: string;
};

type Handler = (body: unknown) => void;

// One connection per tab, shared by every hook. Subscriptions are remembered so they
// come back after a reconnect.
let client: Client | null = null;
let connected = false;
const listeners = new Map<string, Set<Handler>>();
const active = new Map<string, StompSubscription>();
const connectionWatchers = new Set<() => void>();

function setConnected(value: boolean) {
  connected = value;
  connectionWatchers.forEach((watch) => watch());
}

function ensureClient(): Client {
  if (client) return client;
  // The access token lives 15 minutes, so each (re)connect asks for a current one.
  let tokenAttempt = 0;
  client = new Client({
    brokerURL: apiUrl("/ws").replace(/^http/, "ws"),
    reconnectDelay: 3_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async (self) => {
      const token = await getAccessToken(tokenAttempt++ > 0);
      self.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },
    onConnect: () => {
      tokenAttempt = 0;
      active.clear();
      listeners.forEach((_, destination) => subscribeOnServer(destination));
      setConnected(true);
    },
    onWebSocketClose: () => setConnected(false),
  });
  client.activate();
  return client;
}

function subscribeOnServer(destination: string) {
  if (!client?.connected || active.has(destination)) return;
  active.set(
    destination,
    client.subscribe(destination, (message) => {
      const body: unknown = JSON.parse(message.body);
      listeners.get(destination)?.forEach((handler) => handler(body));
    }),
  );
}

function listen(destination: string, handler: Handler): () => void {
  ensureClient();
  let handlers = listeners.get(destination);
  if (!handlers) {
    handlers = new Set();
    listeners.set(destination, handlers);
  }
  handlers.add(handler);
  subscribeOnServer(destination);
  return () => {
    handlers.delete(handler);
    if (handlers.size === 0) {
      listeners.delete(destination);
      active.get(destination)?.unsubscribe();
      active.delete(destination);
    }
  };
}

/** Whether the live connection is up. Callers poll less often while it is. */
export function useRealtimeConnected(): boolean {
  return useSyncExternalStore(watchConnection, () => connected, () => false);
}

function watchConnection(onChange: () => void): () => void {
  ensureClient();
  connectionWatchers.add(onChange);
  return () => {
    connectionWatchers.delete(onChange);
  };
}

/** Calls onQuote with every pushed quote for these symbols. */
export function useLiveQuotes<T>(symbols: string[], onQuote: (quote: T) => void) {
  const callback = useRef(onQuote);
  useEffect(() => {
    callback.current = onQuote;
  });
  const key = [...symbols].sort().join(",");
  useEffect(() => {
    if (!key) return;
    const stops = key
      .split(",")
      .map((symbol) => listen(`/topic/quotes.${symbol}`, (body) => callback.current(body as T)));
    return () => stops.forEach((stop) => stop());
  }, [key]);
}

/** Calls onEvent for each of the signed-in user's order events. */
export function useOrderEvents(onEvent: (event: OrderEvent) => void) {
  const callback = useRef(onEvent);
  useEffect(() => {
    callback.current = onEvent;
  });
  useEffect(
    () => listen("/user/queue/orders", (body) => callback.current(body as OrderEvent)),
    [],
  );
}

/** What core-service sends on /user/queue/backtests when a run finishes. */
export type BacktestEvent = {
  runId: string;
  strategyName: string;
  status: "COMPLETED" | "FAILED";
  totalReturnPct: number | null;
  error: string | null;
};

/** Calls onEvent when one of the signed-in user's backtests completes or fails. */
export function useBacktestEvents(onEvent: (event: BacktestEvent) => void) {
  const callback = useRef(onEvent);
  useEffect(() => {
    callback.current = onEvent;
  });
  useEffect(
    () => listen("/user/queue/backtests", (body) => callback.current(body as BacktestEvent)),
    [],
  );
}

/** Closes the connection, e.g. on sign-out, so the next user starts a fresh one. */
export function disconnectRealtime() {
  const closing = client;
  client = null;
  active.clear();
  setConnected(false);
  void closing?.deactivate();
}
