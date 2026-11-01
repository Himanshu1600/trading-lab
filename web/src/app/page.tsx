"use client";

import Link from "next/link";

import { useAuth } from "@/lib/auth";

const HIGHLIGHTS = [
  { title: "$0 at risk", text: "Paper money only, never real funds." },
  { title: "Your watchlists", text: "Search US stocks and group the ones you follow." },
  { title: "Built to learn", text: "Educational analysis, not investment advice." },
];

export default function Home() {
  const { user, loading } = useAuth();

  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col justify-center gap-10 px-4 py-12">
      <div className="flex flex-col items-center gap-4 text-center">
        <p className="rounded-full bg-accent-soft px-3 py-1 text-xs font-medium text-accent">
          AI paper-trading and strategy lab
        </p>
        <h1 className="max-w-xl text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
          {user ? `Welcome back, ${user.name}` : "Practice trading without the risk"}
        </h1>
        {!loading && (
          <div className="mt-2 flex gap-3">
            {user ? (
              <Link href="/watchlists" className="btn btn-primary">
                Open your watchlists
              </Link>
            ) : (
              <>
                <Link href="/register" className="btn btn-primary">
                  Get started
                </Link>
                <Link href="/login" className="btn btn-secondary">
                  Log in
                </Link>
              </>
            )}
          </div>
        )}
      </div>

      <ul className="grid gap-4 sm:grid-cols-3">
        {HIGHLIGHTS.map((item) => (
          <li key={item.title} className="card p-4">
            <h2 className="font-medium">{item.title}</h2>
            <p className="mt-1 text-sm text-muted">{item.text}</p>
          </li>
        ))}
      </ul>
    </main>
  );
}
