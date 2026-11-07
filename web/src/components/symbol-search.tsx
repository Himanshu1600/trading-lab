"use client";

import { useEffect, useState } from "react";

import { apiFetch } from "@/lib/auth";
import type { Instrument } from "@/lib/market";

export function SymbolSearch({
  actionLabel,
  existing = [],
  onSelect,
}: {
  actionLabel: string;
  existing?: string[];
  onSelect: (instrument: Instrument) => void | Promise<void>;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Instrument[]>([]);
  const [searched, setSearched] = useState("");
  const trimmed = query.trim();

  useEffect(() => {
    if (!trimmed) return;
    let stale = false;
    const timer = setTimeout(() => {
      apiFetch(`/api/v1/instruments?query=${encodeURIComponent(trimmed)}`)
        .then((response) => (response.ok ? response.json() : []))
        .catch(() => [])
        .then((found) => {
          if (!stale) {
            setResults(found);
            setSearched(trimmed);
          }
        });
    }, 250);
    return () => {
      stale = true;
      clearTimeout(timer);
    };
  }, [trimmed]);

  return (
    <div className="relative">
      <input
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setQuery("");
        }}
        placeholder="Search by symbol or company"
        aria-label="Search by symbol or company"
        className="input"
      />
      {trimmed && searched === trimmed && (
        <ul className="card absolute inset-x-0 top-full z-10 mt-1 overflow-hidden p-1 shadow-lg">
          {results.length === 0 && (
            <li className="px-3 py-2 text-sm text-muted">No matches</li>
          )}
          {results.slice(0, 6).map((result) => {
            const added = existing.includes(result.symbol);
            return (
              <li key={result.symbol}>
                <button
                  type="button"
                  disabled={added}
                  onClick={async () => {
                    await onSelect(result);
                    setQuery("");
                  }}
                  className="flex w-full cursor-pointer items-center gap-3 rounded-md px-3 py-2 text-left text-sm hover:bg-background disabled:cursor-default disabled:hover:bg-transparent"
                >
                  <span className="w-20 shrink-0 font-mono font-semibold">
                    {result.symbol}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-muted">
                    {result.name}
                  </span>
                  <span
                    className={
                      added
                        ? "text-xs text-muted"
                        : "text-xs font-medium text-accent"
                    }
                  >
                    {added ? "Added" : actionLabel}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
