"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { useAuth } from "@/lib/auth";

const NAV_LINKS = [
  { href: "/watchlists", label: "Watchlists" },
  { href: "/trade", label: "Trade" },
  { href: "/portfolio", label: "Portfolio" },
];

export function AppHeader() {
  const { user, loading, signOut } = useAuth();
  const pathname = usePathname();

  return (
    <header className="border-b border-border bg-surface">
      <div className="mx-auto flex min-h-14 w-full max-w-4xl flex-wrap items-center gap-x-4 px-4 py-2">
        <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <span
            aria-hidden
            className="flex size-7 items-center justify-center rounded-lg bg-accent text-sm font-bold text-accent-foreground"
          >
            T
          </span>
          Trading Lab
        </Link>

        {user && (
          <nav className="order-last flex w-full gap-1 pt-1 sm:order-none sm:w-auto sm:pt-0">
            {NAV_LINKS.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                aria-current={pathname === link.href ? "page" : undefined}
                className="btn btn-ghost aria-[current=page]:text-accent"
              >
                {link.label}
              </Link>
            ))}
          </nav>
        )}

        <div className="ml-auto flex min-w-0 items-center gap-2">
          {!loading && user && (
            <>
              <span
                title={user.email}
                className="max-w-24 truncate text-sm font-medium sm:max-w-40"
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
