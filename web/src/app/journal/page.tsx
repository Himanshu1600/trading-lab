"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { apiFetch, useRequireAuth } from "@/lib/auth";
import { formatPercent } from "@/lib/backtests";
import { changeClass, formatChange } from "@/lib/market";
import { useOrderEvents } from "@/lib/realtime";

type Stats = {
  fills: number;
  closedTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRatePct: number | null;
  realizedPnl: number;
  averageWin: number | null;
  averageLoss: number | null;
  largestWin: number | null;
  largestLoss: number | null;
  averageReturnPct: number | null;
  averageDaysHeldWinners: number | null;
  averageDaysHeldLosers: number | null;
  symbolsTraded: number;
  mostTradedSymbol: string | null;
  bestSymbol: { symbol: string; realizedPnl: number } | null;
  worstSymbol: { symbol: string; realizedPnl: number } | null;
};

type Journal = {
  stats: Stats;
  fillCount: number;
  insights: string | null;
  insightsCurrent: boolean;
  insightsAt: string | null;
};

function days(value: number | null): string {
  return value === null ? "—" : `${value.toFixed(1)} days`;
}

function money(value: number | null): string {
  return value === null ? "—" : formatChange(value);
}

export default function JournalPage() {
  const user = useRequireAuth();
  const [journal, setJournal] = useState<Journal | null>(null);
  const [coaching, setCoaching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    apiFetch("/api/v1/journal")
      .then((response) => (response.ok ? response.json() : null))
      .then((next: Journal | null) => {
        if (next) setJournal(next);
      })
      .catch(() => setError("Could not load your journal."));
  }, []);

  useEffect(() => {
    if (user) load();
  }, [user, load]);

  // A fill changes the statistics; the server has usually refreshed them by the time this runs.
  useOrderEvents((event) => {
    if (event.kind === "FILLED") load();
  });

  async function coach() {
    setCoaching(true);
    setError(null);
    try {
      const response = await apiFetch("/api/v1/journal/insights", { method: "POST" });
      const body = await response.json().catch(() => null);
      if (response.ok) setJournal(body);
      else setError(body?.detail ?? "The coach couldn't respond. Try again.");
    } catch {
      setError("Can't reach the server. Try again.");
    } finally {
      setCoaching(false);
    }
  }

  if (!user || !journal) return null;
  const stats = journal.stats;

  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 py-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Journal</h1>
        <p className="mt-1 text-sm text-muted">
          How you trade, measured from your paper fills. Each sell counts as one closed trade, measured against your
          average cost.
        </p>
      </div>

      {error && (
        <p role="alert" className="alert">
          {error}
        </p>
      )}

      {stats.fills === 0 ? (
        <p className="card px-4 py-8 text-center text-sm text-muted">
          No trades yet.{" "}
          <Link href="/trade" className="font-medium underline">
            Place your first order
          </Link>
          .
        </p>
      ) : (
        <>
          <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Tile label="Closed trades" value={String(stats.closedTrades)} />
            <Tile label="Winning trades" value={formatPercent(stats.winRatePct)} />
            <Tile label="Realized P&L" value={formatChange(stats.realizedPnl)} valueClass={changeClass(stats.realizedPnl)} />
            <Tile label="Average return per trade" value={formatPercent(stats.averageReturnPct, true)} />
          </ul>

          <section className="card overflow-x-auto">
            <table className="w-full text-sm">
              <tbody className="divide-y divide-border">
                <Row label="Average win" value={money(stats.averageWin)} />
                <Row label="Average loss" value={money(stats.averageLoss)} />
                <Row label="Largest win" value={money(stats.largestWin)} />
                <Row label="Largest loss" value={money(stats.largestLoss)} />
                <Row label="Average time holding winners" value={days(stats.averageDaysHeldWinners)} />
                <Row label="Average time holding losers" value={days(stats.averageDaysHeldLosers)} />
                <Row label="Stocks traded" value={String(stats.symbolsTraded)} />
                <Row label="Most traded" value={stats.mostTradedSymbol ?? "—"} />
                <Row
                  label="Best stock"
                  value={stats.bestSymbol ? `${stats.bestSymbol.symbol} ${formatChange(stats.bestSymbol.realizedPnl)}` : "—"}
                />
                <Row
                  label="Worst stock"
                  value={stats.worstSymbol ? `${stats.worstSymbol.symbol} ${formatChange(stats.worstSymbol.realizedPnl)}` : "—"}
                />
                <Row label="Fills counted" value={String(journal.fillCount)} />
              </tbody>
            </table>
          </section>

          <section aria-label="Coaching" className="card flex flex-col gap-3 p-4">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="font-medium">Coach&apos;s notes</h2>
              {!journal.insightsCurrent && (
                <button onClick={coach} disabled={coaching || stats.closedTrades === 0} className="btn btn-sm btn-secondary">
                  {coaching ? "Thinking…" : journal.insights ? "Refresh notes" : "Get coaching"}
                </button>
              )}
            </div>
            {stats.closedTrades === 0 && (
              <p className="text-sm text-muted">The coach needs at least one closed trade: buy a stock, then sell it.</p>
            )}
            {journal.insights && (
              <>
                <ul className="list-disc space-y-1 pl-5 text-sm leading-relaxed">
                  {journal.insights
                    .split("\n")
                    .map((line) => line.replace(/^\s*-\s*/, "").trim())
                    .filter(Boolean)
                    .map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                </ul>
                <p className="text-xs text-muted">
                  Written by AI from the figures above, each one checked against them.
                  {!journal.insightsCurrent && " You've traded since these notes were written."} Not investment advice.
                </p>
              </>
            )}
          </section>
        </>
      )}
    </main>
  );
}

function Tile({ label, value, valueClass = "" }: { label: string; value: string; valueClass?: string }) {
  return (
    <li className="card min-w-0 p-4">
      <p className="text-xs text-muted">{label}</p>
      <p className={`mt-1 truncate text-lg font-semibold ${valueClass}`}>{value}</p>
    </li>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <tr>
      <th scope="row" className="px-4 py-2 text-left font-normal text-muted">
        {label}
      </th>
      <td className="px-4 py-2 text-right font-medium">{value}</td>
    </tr>
  );
}
