"use client";

import { useMarketStatus } from "@/lib/market";

export function MarketStatusBadge() {
  const status = useMarketStatus();

  if (!status) {
    return null;
  }
  const opens = status.nextOpen
    ? new Date(status.nextOpen).toLocaleString(undefined, {
        weekday: "short",
        hour: "numeric",
        minute: "2-digit",
      })
    : null;

  return (
    <p className="flex items-center gap-2 text-sm text-muted">
      <span
        aria-hidden
        className={`size-2 rounded-full ${status.open ? "bg-accent" : "bg-muted"}`}
      />
      {status.open
        ? "US market open"
        : `US market closed${opens ? `, opens ${opens}` : ""}`}
    </p>
  );
}
