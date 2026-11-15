"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useState } from "react";

import { EquityChart } from "@/components/equity-chart";
import { AiBadge, AiOffNote, aiReady, useAiStatus } from "@/lib/ai";
import { apiFetch, useRequireAuth } from "@/lib/auth";
import {
  EXIT_REASONS,
  formatPercent,
  isFinished,
  type BacktestRun,
  type BacktestTrade,
  type EquityPoint,
  type RunStatus,
} from "@/lib/backtests";
import { changeClass, formatChange, formatMoney } from "@/lib/market";
import { useBacktestEvents } from "@/lib/realtime";

// Results usually arrive over the live connection; this poll covers a dropped message.
const POLL_MS = 2_000;

const STATUS_CLASSES: Record<RunStatus, string> = {
  QUEUED: "bg-background text-muted",
  RUNNING: "bg-accent-soft text-accent",
  COMPLETED: "bg-background text-foreground",
  FAILED: "bg-danger-soft text-danger",
};

export default function BacktestsPage() {
  return (
    <Suspense>
      <BacktestsRoute />
    </Suspense>
  );
}

/** The address decides what's shown: ?run=<id> for one run, nothing for the list. */
function BacktestsRoute() {
  const user = useRequireAuth();
  const runId = useSearchParams().get("run");
  if (!user) return null;
  return runId ? <RunDetail key={runId} runId={runId} /> : <RunList />;
}

function StatusBadge({ status }: { status: RunStatus }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLASSES[status]}`}>
      {status.toLowerCase()}
    </span>
  );
}

function RunList() {
  const [runs, setRuns] = useState<BacktestRun[] | null>(null);

  const load = useCallback(() => {
    apiFetch("/api/v1/backtests")
      .then((response) => (response.ok ? response.json() : []))
      .then(setRuns)
      .catch(() => setRuns([]));
  }, []);

  useEffect(load, [load]);
  useBacktestEvents(load);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Backtests</h1>
        <p className="mt-1 text-sm text-muted">
          Your recent runs. Start a new one from{" "}
          <Link href="/strategies" className="font-medium underline">
            Strategies
          </Link>
          .
        </p>
      </div>
      {runs !== null && runs.length === 0 && (
        <p className="card px-4 py-8 text-center text-sm text-muted">No backtests yet.</p>
      )}
      {runs !== null && runs.length > 0 && (
        <div className="card overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-2.5 font-medium">Strategy</th>
                <th className="px-4 py-2.5 font-medium">Period</th>
                <th className="px-4 py-2.5 text-right font-medium">Return</th>
                <th className="px-4 py-2.5 text-right font-medium">Buy and hold</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {runs.map((run) => {
                const strategy = run.metrics?.strategy;
                const benchmark = run.metrics?.benchmark;
                return (
                  <tr key={run.id}>
                    <td className="max-w-56 truncate px-4 py-2.5">
                      <Link href={`/backtests?run=${run.id}`} className="font-medium hover:underline">
                        {run.strategyName}
                      </Link>
                      <span className="block font-mono text-xs text-muted">{run.params.symbols.join(", ")}</span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-muted">
                      {run.params.from} → {run.params.to}
                    </td>
                    <td className={`px-4 py-2.5 text-right font-medium ${changeClass(strategy?.totalReturnPct ?? 0)}`}>
                      {formatPercent(strategy?.totalReturnPct, true)}
                    </td>
                    <td className="px-4 py-2.5 text-right text-muted">{formatPercent(benchmark?.totalReturnPct, true)}</td>
                    <td className="px-4 py-2.5">
                      <StatusBadge status={run.status} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

function RunDetail({ runId }: { runId: string }) {
  const [run, setRun] = useState<BacktestRun | null>(null);
  const [missing, setMissing] = useState(false);
  const [equity, setEquity] = useState<EquityPoint[]>([]);
  const [trades, setTrades] = useState<BacktestTrade[]>([]);
  const [explanation, setExplanation] = useState<string | null>(null);
  const [explaining, setExplaining] = useState(false);
  const [explainError, setExplainError] = useState<string | null>(null);
  const ai = useAiStatus();

  async function explainRun() {
    setExplaining(true);
    setExplainError(null);
    try {
      const response = await apiFetch(`/api/v1/backtests/${runId}/explain`, { method: "POST" });
      const body = await response.json().catch(() => null);
      if (response.ok) setExplanation(body.explanation);
      else setExplainError(body?.detail ?? "Couldn't explain this backtest.");
    } catch {
      setExplainError("Can't reach the server. Try again.");
    } finally {
      setExplaining(false);
    }
  }

  const load = useCallback(() => {
    apiFetch(`/api/v1/backtests/${runId}`)
      .then(async (response) => {
        if (response.status === 404) {
          setMissing(true);
          return;
        }
        if (!response.ok) return;
        const next: BacktestRun = await response.json();
        if (next.status === "COMPLETED") {
          const [points, made] = await Promise.all([
            apiFetch(`/api/v1/backtests/${runId}/equity`).then((r) => (r.ok ? r.json() : [])),
            apiFetch(`/api/v1/backtests/${runId}/trades`).then((r) => (r.ok ? r.json() : [])),
          ]);
          setEquity(points);
          setTrades(made);
        }
        setRun(next);
      })
      .catch(() => {
        // Kept as it is; the next poll or event tries again.
      });
  }, [runId]);

  useEffect(load, [load]);

  useBacktestEvents((event) => {
    if (event.runId === runId) load();
  });

  const finished = run ? isFinished(run.status) : false;
  useEffect(() => {
    if (!run || finished) return;
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [run, finished, load]);

  if (missing) {
    return (
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <p className="alert">This backtest doesn&apos;t exist, or belongs to someone else.</p>
      </main>
    );
  }
  if (!run) return null;

  const strategy = run.metrics?.strategy;
  const benchmark = run.metrics?.benchmark;

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-2">
        <Link href="/backtests" className="text-sm text-muted hover:underline">
          ← All backtests
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">{run.strategyName}</h1>
          <StatusBadge status={run.status} />
        </div>
        <p className="text-sm text-muted">
          <span className="font-mono">{run.params.symbols.join(", ")}</span> · {run.params.from} to{" "}
          {run.params.to} · {formatMoney(run.params.initialCash)} · fee {run.params.feeBps} bps · slippage{" "}
          {run.params.slippageBps} bps
          {run.durationMs !== null && ` · computed in ${run.durationMs} ms`}
        </p>
      </div>

      {!finished && (
        <p className="card px-4 py-8 text-center text-sm text-muted">
          {run.status === "QUEUED" ? "Waiting to start…" : "Running… loading prices and replaying the strategy."}
        </p>
      )}

      {run.status === "FAILED" && (
        <p role="alert" className="alert">
          {run.error ?? "The backtest failed."}
        </p>
      )}

      {strategy && benchmark && (
        <>
          <section aria-label="Results" className="card overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-border text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-2.5 font-medium" />
                  <th className="px-4 py-2.5 text-right font-medium">Strategy</th>
                  <th className="px-4 py-2.5 text-right font-medium">Buy and hold</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                <MetricRow label="End value" a={formatMoney(strategy.endEquity)} b={formatMoney(benchmark.endEquity)} />
                <MetricRow
                  label="Total return"
                  a={formatPercent(strategy.totalReturnPct, true)}
                  b={formatPercent(benchmark.totalReturnPct, true)}
                  highlight={strategy.totalReturnPct}
                />
                <MetricRow label="Yearly return (CAGR)" a={formatPercent(strategy.cagrPct, true)} b={formatPercent(benchmark.cagrPct, true)} />
                <MetricRow label="Sharpe ratio" a={strategy.sharpe.toFixed(2)} b={benchmark.sharpe.toFixed(2)} />
                <MetricRow label="Worst drop from a peak" a={formatPercent(-strategy.maxDrawdownPct)} b={formatPercent(-benchmark.maxDrawdownPct)} />
                <MetricRow label="Trades" a={String(strategy.trades ?? 0)} b="—" />
                <MetricRow label="Winning trades" a={formatPercent(strategy.winRatePct)} b="—" />
                <MetricRow
                  label="Profit factor"
                  a={strategy.profitFactor !== null ? strategy.profitFactor.toFixed(2) : strategy.trades ? "No losses" : "—"}
                  b="—"
                />
                <MetricRow label="Days holding a position" a={formatPercent(strategy.exposurePct)} b="100.00%" />
              </tbody>
            </table>
          </section>

          <section aria-label="Explanation" className="card flex flex-col gap-2 p-4">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="font-medium">In plain English</h2>
              <AiBadge status={ai} />
              {!(explanation ?? run.explanation) && (
                <button onClick={explainRun} disabled={explaining || !aiReady(ai)} className="btn btn-sm btn-secondary">
                  {explaining ? "Explaining…" : "Explain these results"}
                </button>
              )}
            </div>
            {(explanation ?? run.explanation) && (
              <div className="flex flex-col gap-2 text-sm leading-relaxed">
                {(explanation ?? run.explanation ?? "").split(/\n\s*\n/).map((paragraph, index) => (
                  <p key={index}>{paragraph}</p>
                ))}
                <p className="text-xs text-muted">
                  Written by AI from the numbers above, which were calculated by the app. Every number in it
                  was checked against them. Not investment advice.
                </p>
              </div>
            )}
            {!(explanation ?? run.explanation) && <AiOffNote status={ai} />}
            {explainError && (
              <p role="alert" className="alert">
                {explainError}
              </p>
            )}
          </section>

          <section aria-label="Equity curve" className="card p-4">
            <EquityChart points={equity} />
          </section>

          <TradeTable trades={trades} />

          <p className="text-xs text-muted">
            Past results don&apos;t predict future ones. Backtests here use today&apos;s list of stocks
            (survivorship bias), split- and dividend-adjusted prices, and whole shares only. Rules are
            checked at each close and orders fill at the next open, so a strategy never trades on prices
            it couldn&apos;t have known.
          </p>
        </>
      )}
    </main>
  );
}

function MetricRow({ label, a, b, highlight }: { label: string; a: string; b: string; highlight?: number }) {
  return (
    <tr>
      <th scope="row" className="px-4 py-2 text-left font-normal text-muted">
        {label}
      </th>
      <td className={`px-4 py-2 text-right font-medium ${highlight !== undefined ? changeClass(highlight) : ""}`}>{a}</td>
      <td className="px-4 py-2 text-right text-muted">{b}</td>
    </tr>
  );
}

function TradeTable({ trades }: { trades: BacktestTrade[] }) {
  if (trades.length === 0) {
    return <p className="card px-4 py-6 text-center text-sm text-muted">The entry rules never matched in this period.</p>;
  }
  return (
    <section className="card overflow-x-auto">
      <h2 className="border-b border-border px-4 py-3 font-medium">Trades ({trades.length})</h2>
      <table className="w-full text-sm">
        <thead className="border-b border-border text-left text-xs text-muted">
          <tr>
            <th className="px-4 py-2 font-medium">Stock</th>
            <th className="px-4 py-2 text-right font-medium">Shares</th>
            <th className="px-4 py-2 font-medium">Bought</th>
            <th className="px-4 py-2 font-medium">Sold</th>
            <th className="px-4 py-2 text-right font-medium">P&amp;L</th>
            <th className="px-4 py-2 font-medium">Why it closed</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {trades.map((trade, index) => (
            <tr key={index}>
              <td className="px-4 py-2 font-mono font-semibold">{trade.symbol}</td>
              <td className="px-4 py-2 text-right">{trade.qty}</td>
              <td className="whitespace-nowrap px-4 py-2">
                {trade.entryDate} <span className="text-muted">at {formatMoney(trade.entryPrice)}</span>
              </td>
              <td className="whitespace-nowrap px-4 py-2">
                {trade.exitDate} <span className="text-muted">at {formatMoney(trade.exitPrice)}</span>
              </td>
              <td className={`whitespace-nowrap px-4 py-2 text-right font-medium ${changeClass(trade.pnl)}`}>
                {formatChange(trade.pnl)}{" "}
                <span className="text-xs">({formatPercent(trade.returnPct, true)})</span>
              </td>
              <td className="px-4 py-2 text-muted">{EXIT_REASONS[trade.exitReason]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

