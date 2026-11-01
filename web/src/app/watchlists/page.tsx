"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { apiFetch, useAuth } from "@/lib/auth";

type Instrument = { symbol: string; name: string; exchange: string };
type Watchlist = { id: string; name: string; items: Instrument[] };

const jsonHeaders = { "Content-Type": "application/json" };

export default function WatchlistsPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [lists, setLists] = useState<Watchlist[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    apiFetch("/api/v1/watchlists")
      .then((response) => response.json())
      .then(setLists)
      .catch(() => setError("Could not load your watchlists."));
  }, [user, loading, router]);

  // Runs a request that returns one watchlist, then swaps it into the page.
  async function update(path: string, init: RequestInit) {
    setError(null);
    const response = await apiFetch(path, init);
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      setError(problem?.detail ?? "That didn't work. Try again.");
      return null;
    }
    return (await response.json()) as Watchlist;
  }

  function replaceList(updated: Watchlist | null) {
    if (updated) {
      setLists((all) =>
        (all ?? []).map((list) => (list.id === updated.id ? updated : list)),
      );
    }
  }

  async function createList(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const name = String(new FormData(form).get("name"));
    const created = await update("/api/v1/watchlists", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ name }),
    });
    if (created) {
      setLists((all) => [...(all ?? []), created]);
      form.reset();
    }
  }

  async function deleteList(id: string) {
    setError(null);
    const response = await apiFetch(`/api/v1/watchlists/${id}`, {
      method: "DELETE",
    });
    if (response.ok) {
      setLists((all) => (all ?? []).filter((list) => list.id !== id));
    } else {
      setError("Could not delete that watchlist.");
    }
  }

  function itemPath(listId: string, symbol: string) {
    return `/api/v1/watchlists/${listId}/items/${encodeURIComponent(symbol)}`;
  }

  if (loading || !user) {
    return null;
  }

  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Watchlists</h1>
          <p className="mt-1 text-sm text-muted">
            Group the stocks you want to follow.
          </p>
        </div>
        <form onSubmit={createList} className="flex gap-2 sm:w-80">
          <input
            name="name"
            required
            maxLength={50}
            placeholder="New watchlist name"
            aria-label="New watchlist name"
            className="input"
          />
          <button type="submit" className="btn btn-primary">
            Create
          </button>
        </form>
      </div>

      {error && (
        <p role="alert" className="alert">
          {error}
        </p>
      )}

      {lists === null && !error && <p className="text-sm text-muted">Loading…</p>}

      {lists?.length === 0 && (
        <div className="rounded-xl border border-dashed border-border px-4 py-12 text-center">
          <p className="font-medium">No watchlists yet</p>
          <p className="mt-1 text-sm text-muted">
            Create your first one to start following stocks.
          </p>
        </div>
      )}

      {lists?.map((list) => (
        <WatchlistCard
          key={list.id}
          list={list}
          onDelete={() => deleteList(list.id)}
          onAdd={async (symbol) =>
            replaceList(await update(itemPath(list.id, symbol), { method: "POST" }))
          }
          onRemove={async (symbol) =>
            replaceList(await update(itemPath(list.id, symbol), { method: "DELETE" }))
          }
        />
      ))}
    </main>
  );
}

function WatchlistCard({
  list,
  onDelete,
  onAdd,
  onRemove,
}: {
  list: Watchlist;
  onDelete: () => Promise<void>;
  onAdd: (symbol: string) => Promise<void>;
  onRemove: (symbol: string) => Promise<void>;
}) {
  const [confirming, setConfirming] = useState(false);

  return (
    <section className="card">
      <div className="flex items-center gap-3 border-b border-border px-4 py-3">
        <h2 className="min-w-0 truncate font-medium">{list.name}</h2>
        <span className="rounded-full bg-accent-soft px-2 py-0.5 text-xs font-medium text-accent">
          {list.items.length} {list.items.length === 1 ? "stock" : "stocks"}
        </span>
        <div className="ml-auto flex shrink-0 items-center gap-1">
          {confirming ? (
            <>
              <button
                onClick={() => setConfirming(false)}
                className="btn btn-sm btn-ghost"
              >
                Cancel
              </button>
              <button onClick={onDelete} className="btn btn-sm btn-danger">
                Confirm delete
              </button>
            </>
          ) : (
            <button
              onClick={() => setConfirming(true)}
              aria-label={`Delete ${list.name}`}
              className="btn btn-sm btn-danger-ghost"
            >
              Delete
            </button>
          )}
        </div>
      </div>

      <div className="p-4">
        <SymbolSearch
          existing={list.items.map((item) => item.symbol)}
          onAdd={onAdd}
        />
      </div>

      {list.items.length === 0 ? (
        <p className="px-4 pb-4 text-sm text-muted">
          Search above to add your first stock.
        </p>
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {list.items.map((item) => (
            <li key={item.symbol} className="flex items-center gap-3 px-4 py-2.5">
              <span className="w-20 shrink-0 font-mono text-sm font-semibold">
                {item.symbol}
              </span>
              <span className="min-w-0 flex-1 truncate text-sm text-muted">
                {item.name}
              </span>
              <span className="hidden text-xs text-muted sm:inline">
                {item.exchange}
              </span>
              <button
                onClick={() => onRemove(item.symbol)}
                aria-label={`Remove ${item.symbol}`}
                className="btn btn-sm btn-danger-ghost"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function SymbolSearch({
  existing,
  onAdd,
}: {
  existing: string[];
  onAdd: (symbol: string) => Promise<void>;
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
                  disabled={added}
                  onClick={async () => {
                    await onAdd(result.symbol);
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
                    {added ? "Added" : "Add"}
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
