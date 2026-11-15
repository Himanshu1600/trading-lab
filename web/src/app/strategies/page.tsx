"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";

import { AiBadge, AiOffNote, aiReady, useAiStatus } from "@/lib/ai";
import { apiFetch, useRequireAuth } from "@/lib/auth";
import { TEMPLATES, type SavedStrategy } from "@/lib/backtests";

type Message = { kind: "error" | "ok"; text: string };

function pretty(dsl: Record<string, unknown>): string {
  return JSON.stringify(dsl, null, 2);
}

/** The server's ProblemDetail message. For an invalid strategy it starts with the failing field. */
async function problemText(response: Response, fallback: string): Promise<string> {
  const problem = await response.json().catch(() => null);
  return problem?.detail ?? fallback;
}

export default function StrategiesPage() {
  const user = useRequireAuth();
  const router = useRouter();
  const [strategies, setStrategies] = useState<SavedStrategy[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [text, setText] = useState(pretty(TEMPLATES[0].dsl));
  const [message, setMessage] = useState<Message | null>(null);
  const [busy, setBusy] = useState(false);
  const ai = useAiStatus();
  const [description, setDescription] = useState("");
  const [drafting, setDrafting] = useState(false);
  // The description an AI draft came from, saved with the strategy so you can see where it started.
  const [sourcePrompt, setSourcePrompt] = useState<string | null>(null);
  const [aiNote, setAiNote] = useState<{ question?: string; assumptions: string[] } | null>(null);

  // Empty dates let the server choose: the last 3 years, ending today in New York.
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [initialCash, setInitialCash] = useState("100000");
  const [feeBps, setFeeBps] = useState("0");
  const [slippageBps, setSlippageBps] = useState("5");

  const load = useCallback(() => {
    apiFetch("/api/v1/strategies")
      .then((response) => (response.ok ? response.json() : []))
      .then(setStrategies)
      .catch(() => setStrategies([]));
  }, []);

  useEffect(() => {
    if (user) load();
  }, [user, load]);

  function select(strategy: SavedStrategy | null) {
    setSelectedId(strategy?.id ?? null);
    setText(pretty(strategy?.dsl ?? TEMPLATES[0].dsl));
    setMessage(null);
    setSourcePrompt(null);
    setAiNote(null);
  }

  async function draftFromDescription(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setDrafting(true);
    setMessage(null);
    setAiNote(null);
    try {
      const response = await apiFetch("/api/v1/ai/strategies/draft", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: description }),
      });
      if (!response.ok) {
        setMessage({ kind: "error", text: await problemText(response, "The AI couldn't draft that.") });
        return;
      }
      const draft: {
        kind: "strategy" | "clarification" | "invalid";
        question: string | null;
        dsl: Record<string, unknown> | null;
        assumptions: string[];
        error: string | null;
      } = await response.json();
      if (draft.kind === "clarification") {
        setAiNote({ question: draft.question ?? undefined, assumptions: [] });
        return;
      }
      setSelectedId(null);
      setText(pretty(draft.dsl ?? {}));
      setSourcePrompt(description);
      setAiNote({ assumptions: draft.assumptions });
      setMessage(
        draft.kind === "invalid"
          ? { kind: "error", text: `The draft needs one fix before saving: ${draft.error}` }
          : { kind: "ok", text: "Drafted. Review the rules below, then save." },
      );
    } catch {
      setMessage({ kind: "error", text: "Can't reach the server. Try again." });
    } finally {
      setDrafting(false);
    }
  }

  /** Parses the editor's text, reporting JSON syntax errors before anything reaches the server. */
  function readDsl(): Record<string, unknown> | null {
    try {
      const parsed: unknown = JSON.parse(text);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
      setMessage({ kind: "error", text: "The strategy must be a JSON object." });
    } catch (error) {
      setMessage({ kind: "error", text: `That isn't valid JSON: ${(error as Error).message}` });
    }
    return null;
  }

  async function send(path: string, method: string): Promise<Response | null> {
    const dsl = readDsl();
    if (!dsl) return null;
    setBusy(true);
    setMessage(null);
    try {
      return await apiFetch(path, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dsl, ...(!selectedId && sourcePrompt && { sourcePrompt }) }),
      });
    } catch {
      setMessage({ kind: "error", text: "Can't reach the server. Try again." });
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function check() {
    const response = await send("/api/v1/strategies/validate", "POST");
    if (!response) return;
    setMessage(
      response.ok
        ? { kind: "ok", text: "This strategy is valid." }
        : { kind: "error", text: await problemText(response, "This strategy isn't valid.") },
    );
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const response = await send(
      selectedId ? `/api/v1/strategies/${selectedId}` : "/api/v1/strategies",
      selectedId ? "PUT" : "POST",
    );
    if (!response) return;
    if (response.ok) {
      const saved: SavedStrategy = await response.json();
      setSelectedId(saved.id);
      setText(pretty(saved.dsl));
      setMessage({ kind: "ok", text: `Saved "${saved.name}".` });
      load();
    } else {
      setMessage({ kind: "error", text: await problemText(response, "Couldn't save it.") });
    }
  }

  async function remove() {
    if (!selectedId) return;
    const response = await apiFetch(`/api/v1/strategies/${selectedId}`, { method: "DELETE" });
    if (response.ok || response.status === 404) {
      select(null);
      load();
    } else {
      setMessage({ kind: "error", text: "Couldn't delete it." });
    }
  }

  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedId) return;
    setBusy(true);
    setMessage(null);
    try {
      const response = await apiFetch("/api/v1/backtests", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          strategyId: selectedId,
          ...(from && { from }),
          ...(to && { to }),
          initialCash: Number(initialCash),
          feeBps: Number(feeBps),
          slippageBps: Number(slippageBps),
        }),
      });
      if (response.ok) {
        const started: { id: string } = await response.json();
        router.push(`/backtests?run=${started.id}`);
      } else {
        setMessage({ kind: "error", text: await problemText(response, "Couldn't start the backtest.") });
      }
    } catch {
      setMessage({ kind: "error", text: "Can't reach the server. Try again." });
    } finally {
      setBusy(false);
    }
  }

  if (!user) {
    return null;
  }

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Strategies</h1>
        <p className="mt-1 text-sm text-muted">
          Write trading rules as JSON, then test them on past prices. Rules are checked at each
          day&apos;s close and trade at the next day&apos;s open.
        </p>
      </div>

      <form onSubmit={draftFromDescription} className="card flex flex-col gap-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="describe" className="font-medium">
            Describe a strategy
          </label>
          <AiBadge status={ai} />
        </div>
        <textarea
          id="describe"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          rows={2}
          maxLength={2000}
          placeholder="Buy Apple when the 20-day average crosses above the 50-day, sell when it crosses back, with a 5% stop-loss"
          className="input resize-y"
        />
        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" disabled={drafting || !aiReady(ai) || description.trim().length < 3} className="btn btn-primary">
            {drafting ? "Drafting…" : "Draft with AI"}
          </button>
          <span className="text-xs text-muted">
            The AI writes the rules; nothing is saved until you check and save them. It gives no investment advice.
          </span>
        </div>
        <AiOffNote status={ai} />
        {aiNote?.question && (
          <p role="status" className="rounded-lg bg-accent-soft px-3 py-2 text-sm text-accent">
            {aiNote.question}
          </p>
        )}
        {aiNote && aiNote.assumptions.length > 0 && (
          <div className="text-sm">
            <p className="font-medium">Assumptions the AI made</p>
            <ul className="mt-1 list-disc pl-5 text-muted">
              {aiNote.assumptions.map((assumption) => (
                <li key={assumption}>{assumption}</li>
              ))}
            </ul>
          </div>
        )}
      </form>

      <div className="grid items-start gap-6 md:grid-cols-[16rem_1fr]">
        <section className="card flex flex-col">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <h2 className="font-medium">Saved</h2>
            <button onClick={() => select(null)} className="btn btn-sm btn-secondary">
              New
            </button>
          </div>
          {strategies.length === 0 ? (
            <p className="px-4 py-6 text-sm text-muted">No strategies yet. Edit a template and save it.</p>
          ) : (
            <ul className="divide-y divide-border">
              {strategies.map((strategy) => (
                <li key={strategy.id}>
                  <button
                    onClick={() => select(strategy)}
                    aria-current={strategy.id === selectedId ? "true" : undefined}
                    className="w-full px-4 py-2.5 text-left text-sm hover:bg-background aria-current:bg-accent-soft"
                  >
                    <span className="block truncate font-medium">{strategy.name}</span>
                    <span className="block truncate font-mono text-xs text-muted">
                      {((strategy.dsl.symbols as string[]) ?? []).join(", ")}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <div className="flex min-w-0 flex-col gap-6">
          <form onSubmit={save} className="card flex flex-col gap-3 p-4">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="mr-auto font-medium">{selectedId ? "Edit strategy" : "New strategy"}</h2>
              {!selectedId && (
                <select
                  aria-label="Start from a template"
                  onChange={(event) => setText(pretty(TEMPLATES[Number(event.target.value)].dsl))}
                  className="input w-auto py-1.5 text-sm"
                >
                  {TEMPLATES.map((template, index) => (
                    <option key={template.label} value={index}>
                      {template.label}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <textarea
              aria-label="Strategy JSON"
              value={text}
              onChange={(event) => setText(event.target.value)}
              spellCheck={false}
              rows={22}
              className="input resize-y font-mono text-xs leading-relaxed"
            />
            {message && (
              <p
                role={message.kind === "error" ? "alert" : "status"}
                className={
                  message.kind === "error"
                    ? "alert"
                    : "rounded-lg bg-accent-soft px-3 py-2 text-sm text-accent"
                }
              >
                {message.text}
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <button type="submit" disabled={busy} className="btn btn-primary">
                {selectedId ? "Save changes" : "Save"}
              </button>
              <button type="button" onClick={check} disabled={busy} className="btn btn-secondary">
                Check
              </button>
              {selectedId && (
                <button type="button" onClick={remove} className="btn btn-danger-ghost ml-auto">
                  Delete
                </button>
              )}
            </div>
            <details className="text-sm text-muted">
              <summary className="cursor-pointer font-medium text-foreground">What can a strategy use?</summary>
              <ul className="mt-2 list-disc space-y-1 pl-5">
                <li>
                  Conditions: <code>crossesAbove</code>, <code>crossesBelow</code>, <code>gt</code>,{" "}
                  <code>lt</code>, and <code>all</code>, <code>any</code>, <code>not</code> to combine them.
                </li>
                <li>
                  Values: <code>{"{\"price\": \"close\"}"}</code> (or open, high, low), a number, or an
                  indicator: SMA, EMA, RSI and ATR with a <code>period</code>; MACD with{" "}
                  <code>fast</code>, <code>slow</code>, <code>signal</code> and an <code>output</code>;
                  BOLLINGER with <code>period</code>, <code>stdDev</code> and an <code>output</code>.
                </li>
                <li>
                  Risk: <code>stopLossPct</code> and <code>takeProfitPct</code>. Sizing:{" "}
                  <code>percentOfEquity</code>, <code>fixedQty</code> or <code>fixedCash</code>, with an
                  optional <code>maxPositions</code>.
                </li>
              </ul>
            </details>
          </form>

          {selectedId && (
            <form onSubmit={run} className="card flex flex-col gap-4 p-4">
              <h2 className="font-medium">Run a backtest</h2>
              <div className="grid gap-3 sm:grid-cols-3">
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  From
                  <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="input font-normal" />
                </label>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  To
                  <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="input font-normal" />
                </label>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  Starting cash
                  <input
                    type="number"
                    required
                    min={1000}
                    step={1000}
                    value={initialCash}
                    onChange={(e) => setInitialCash(e.target.value)}
                    className="input font-normal"
                  />
                </label>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  Fee per trade (bps)
                  <input
                    type="number"
                    required
                    min={0}
                    max={100}
                    step={0.5}
                    value={feeBps}
                    onChange={(e) => setFeeBps(e.target.value)}
                    className="input font-normal"
                  />
                </label>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  Slippage (bps)
                  <input
                    type="number"
                    required
                    min={0}
                    max={100}
                    step={0.5}
                    value={slippageBps}
                    onChange={(e) => setSlippageBps(e.target.value)}
                    className="input font-normal"
                  />
                </label>
              </div>
              <p className="text-xs text-muted">
                Leave the dates empty for the last 3 years. 1 basis point (bp) is 0.01% of each
                trade&apos;s value. History goes back 5 years, and the
                first run for a stock downloads it, which takes a few seconds.
              </p>
              <div>
                <button type="submit" disabled={busy} className="btn btn-primary">
                  Run backtest
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </main>
  );
}
