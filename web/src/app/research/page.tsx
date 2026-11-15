"use client";

import { useState, type FormEvent } from "react";

import { SymbolSearch } from "@/components/symbol-search";
import { apiFetch, useRequireAuth } from "@/lib/auth";

type Answer = {
  symbol: string;
  segments: { text: string; citations: { source: number; quote: string }[] }[];
  sources: { number: number; title: string; section: string; formType: string; filedAt: string; url: string }[];
};

const EXAMPLES = ["What are the biggest risks the company describes?", "How does the company make money?", "What changed in the latest quarter?"];

export default function ResearchPage() {
  const user = useRequireAuth();
  const [symbol, setSymbol] = useState("");
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAsking(true);
    setError(null);
    setAnswer(null);
    try {
      const response = await apiFetch("/api/v1/ai/copilot/ask", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ symbol, question }),
      });
      const body = await response.json().catch(() => null);
      if (response.ok) setAnswer(body);
      else setError(body?.detail ?? "The copilot couldn't answer. Try again.");
    } catch {
      setError("Can't reach the server. Try again.");
    } finally {
      setAsking(false);
    }
  }

  if (!user) return null;

  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 py-8">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Research</h1>
        <p className="mt-1 text-sm text-muted">
          Ask about a company. Answers come only from its latest 10-K and 10-Q filed with the SEC, and every claim links
          to the passage it came from.
        </p>
      </div>

      <section className="card flex flex-col gap-4 p-4">
        <SymbolSearch actionLabel="Select" onSelect={(selected) => setSymbol(selected.symbol)} />
        {symbol && (
          <form onSubmit={ask} className="flex flex-col gap-3">
            <label htmlFor="question" className="text-sm font-medium">
              Your question about <span className="font-mono">{symbol}</span>
            </label>
            <textarea
              id="question"
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              rows={2}
              maxLength={1000}
              className="input resize-y"
            />
            <div className="flex flex-wrap gap-2">
              {EXAMPLES.map((example) => (
                <button key={example} type="button" onClick={() => setQuestion(example)} className="btn btn-sm btn-ghost">
                  {example}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <button type="submit" disabled={asking || question.trim().length < 3} className="btn btn-primary">
                {asking ? "Reading the filings…" : "Ask"}
              </button>
              <span className="text-xs text-muted">The first question about a stock downloads its filings, which takes a little longer.</span>
            </div>
          </form>
        )}
      </section>

      {error && (
        <p role="alert" className="alert">
          {error}
        </p>
      )}

      {answer && (
        <section aria-label="Answer" className="card flex flex-col gap-4 p-4">
          <div className="text-sm leading-relaxed whitespace-pre-line">
            {answer.segments.map((segment, index) => (
              <span key={index}>
                {segment.text}
                {[...new Set(segment.citations.map((citation) => citation.source))].map((number) => (
                  <a
                    key={number}
                    href={`#source-${number}`}
                    title={segment.citations.find((citation) => citation.source === number)?.quote}
                    className="ml-0.5 rounded bg-accent-soft px-1 align-super text-xs font-medium text-accent"
                  >
                    {number}
                  </a>
                ))}
              </span>
            ))}
          </div>
          {answer.sources.length > 0 && (
            <ol className="flex flex-col gap-1 border-t border-border pt-3 text-xs text-muted">
              {answer.sources.map((source) => (
                <li key={source.number} id={`source-${source.number}`}>
                  <span className="font-medium text-foreground">[{source.number}]</span> {source.section},{" "}
                  <a href={source.url} target="_blank" rel="noreferrer" className="underline">
                    {source.title}
                  </a>
                </li>
              ))}
            </ol>
          )}
          <p className="text-xs text-muted">Written by AI from SEC filings. Hover a number to see the quoted passage. Not investment advice.</p>
        </section>
      )}
    </main>
  );
}
