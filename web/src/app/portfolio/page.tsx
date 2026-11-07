"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { MarketStatusBadge } from "@/components/market-status";
import { apiFetch, useRequireAuth } from "@/lib/auth";
import { changeClass, formatChange, formatMoney } from "@/lib/market";

type Account = {
  cash: number;
  reservedCash: number;
  buyingPower: number;
  holdingsValue: number;
  equity: number;
  startingCash: number;
  realizedPnl: number;
  unrealizedPnl: number;
};

type Position = {
  symbol: string;
  qty: number;
  availableQty: number;
  avgCost: number;
  last: number | null;
  marketValue: number;
  unrealizedPnl: number;
  realizedPnl: number;
};

type Order = {
  id: string;
  symbol: string;
  side: "BUY" | "SELL";
  type: "MARKET" | "LIMIT";
  qty: number;
  limitPrice: number | null;
  status: "OPEN" | "FILLED" | "CANCELLED" | "REJECTED";
  avgFillPrice: number | null;
  rejectReason: string | null;
  createdAt: string;
};

const REFRESH_MS = 5_000;

const STATUS_CLASSES: Record<Order["status"], string> = {
  OPEN: "bg-accent-soft text-accent",
  FILLED: "bg-background text-foreground",
  CANCELLED: "bg-background text-muted",
  REJECTED: "bg-danger-soft text-danger",
};

async function readJson<T>(path: string): Promise<T | null> {
  const response = await apiFetch(path);
  return response.ok ? ((await response.json()) as T) : null;
}

export default function PortfolioPage() {
  const user = useRequireAuth();
  const [account, setAccount] = useState<Account | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    Promise.all([
      readJson<Account>("/api/v1/account"),
      readJson<Position[]>("/api/v1/positions"),
      readJson<Order[]>("/api/v1/orders"),
    ])
      .then(([nextAccount, nextPositions, nextOrders]) => {
        if (nextAccount) setAccount(nextAccount);
        if (nextPositions) setPositions(nextPositions);
        if (nextOrders) setOrders(nextOrders);
      })
      .catch(() => setError("Could not refresh your portfolio."));
  }, []);

  useEffect(() => {
    if (!user) return;
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [user, load]);

  async function cancel(id: string) {
    setError(null);
    const response = await apiFetch(`/api/v1/orders/${id}`, { method: "DELETE" });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      setError(problem?.detail ?? "Could not cancel that order.");
    }
    load();
  }

  if (!user) {
    return null;
  }

  const openOrders = orders.filter((order) => order.status === "OPEN");
  const history = orders.filter((order) => order.status !== "OPEN");
  const totalPnl = account ? account.equity - account.startingCash : 0;

  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Portfolio</h1>
          <p className="mt-1 text-sm text-muted">
            Paper money only. Values refresh every few seconds.
          </p>
        </div>
        <MarketStatusBadge />
      </div>

      {error && (
        <p role="alert" className="alert">
          {error}
        </p>
      )}

      {account && (
        <ul className="grid grid-cols-2 gap-4 md:grid-cols-4">
          <Tile label="Total value" value={formatMoney(account.equity)} />
          <Tile
            label="Total gain or loss"
            value={formatChange(totalPnl)}
            valueClass={changeClass(totalPnl)}
          />
          <Tile label="Buying power" value={formatMoney(account.buyingPower)} />
          <Tile label="Cash" value={formatMoney(account.cash)} />
        </ul>
      )}

      <section className="card">
        <h2 className="border-b border-border px-4 py-3 font-medium">
          Positions
        </h2>
        {positions.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-muted">
            You don&apos;t own any stocks yet.{" "}
            <Link href="/trade" className="font-medium text-accent hover:underline">
              Place your first trade
            </Link>
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-xl text-sm">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">Symbol</th>
                  <th className="px-4 py-2 text-right font-medium">Shares</th>
                  <th className="px-4 py-2 text-right font-medium">Avg cost</th>
                  <th className="px-4 py-2 text-right font-medium">Price</th>
                  <th className="px-4 py-2 text-right font-medium">Value</th>
                  <th className="px-4 py-2 text-right font-medium">Gain or loss</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border border-t border-border">
                {positions.map((position) => (
                  <tr key={position.symbol}>
                    <td className="px-4 py-2.5 font-mono font-semibold">
                      {position.symbol}
                    </td>
                    <td className="px-4 py-2.5 text-right">{position.qty}</td>
                    <td className="px-4 py-2.5 text-right">
                      {formatMoney(position.avgCost)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {position.last !== null ? formatMoney(position.last) : "—"}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {formatMoney(position.marketValue)}
                    </td>
                    <td
                      className={`px-4 py-2.5 text-right font-medium ${changeClass(position.unrealizedPnl)}`}
                    >
                      {formatChange(position.unrealizedPnl)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Link
                        href={`/trade?symbol=${encodeURIComponent(position.symbol)}&side=SELL`}
                        aria-label={`Sell ${position.symbol}`}
                        className="btn btn-sm btn-secondary"
                      >
                        Sell
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {openOrders.length > 0 && (
        <section className="card">
          <h2 className="border-b border-border px-4 py-3 font-medium">
            Open orders
          </h2>
          <ul className="divide-y divide-border">
            {openOrders.map((order) => (
              <li key={order.id} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                <OrderSummary order={order} />
                <button
                  onClick={() => cancel(order.id)}
                  aria-label={`Cancel ${order.side} ${order.symbol}`}
                  className="btn btn-sm btn-danger-ghost ml-auto"
                >
                  Cancel
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {history.length > 0 && (
        <section className="card">
          <h2 className="border-b border-border px-4 py-3 font-medium">
            Order history
          </h2>
          <ul className="divide-y divide-border">
            {history.map((order) => (
              <li key={order.id} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                <OrderSummary order={order} />
                <span
                  title={order.rejectReason ?? undefined}
                  className={`ml-auto rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLASSES[order.status]}`}
                >
                  {order.status.toLowerCase()}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}

function Tile({
  label,
  value,
  valueClass = "",
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <li className="card min-w-0 p-4">
      <p className="text-xs text-muted">{label}</p>
      <p className={`mt-1 truncate text-lg font-semibold ${valueClass}`}>{value}</p>
    </li>
  );
}

function OrderSummary({ order }: { order: Order }) {
  const price = order.avgFillPrice ?? order.limitPrice;

  return (
    <span className="min-w-0 truncate">
      <span className="font-medium">{order.side === "BUY" ? "Buy" : "Sell"}</span>{" "}
      {order.qty} <span className="font-mono font-semibold">{order.symbol}</span>
      <span className="text-muted">
        {" "}
        {price !== null ? `at ${formatMoney(price)}` : "at market"}
      </span>
    </span>
  );
}
