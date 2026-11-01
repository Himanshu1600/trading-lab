"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { useAuth } from "@/lib/auth";

export function AppHeader() {
  const { user, loading, signOut } = useAuth();
  const pathname = usePathname();

  return (
    <header className="border-b border-border bg-surface">
      <div className="mx-auto flex h-14 w-full max-w-4xl items-center gap-4 px-4">
        <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <span
            aria-hidden
            className="flex size-7 items-center justify-center rounded-lg bg-accent text-sm font-bold text-accent-foreground"
          >
            T
          </span>
          <span className={user ? "hidden sm:inline" : undefined}>Trading Lab</span>
        </Link>

        {user && (
          <nav>
            <Link
              href="/watchlists"
              aria-current={pathname === "/watchlists" ? "page" : undefined}
              className="btn btn-ghost aria-[current=page]:text-accent"
            >
              Watchlists
            </Link>
          </nav>
        )}

        <div className="ml-auto flex min-w-0 items-center gap-2">
          {!loading && user && (
            <>
              <span
                title={user.email}
                className="max-w-24 truncate text-sm font-medium sm:max-w-48"
              >
                {user.name}
              </span>
              <button onClick={signOut} className="btn btn-secondary">
                Log out
              </button>
            </>
          )}
          {!loading && !user && (
            <>
              <Link href="/login" className="btn btn-ghost">
                Log in
              </Link>
              <Link href="/register" className="btn btn-primary">
                Sign up
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
