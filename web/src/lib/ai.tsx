"use client";

import { useEffect, useState } from "react";

import { apiFetch } from "@/lib/auth";

/** What core-service reports from GET /api/v1/ai/status. */
export type AiStatus = {
  serviceConfigured: boolean;
  serviceReachable: boolean;
  claude: boolean;
  research: boolean;
};

export type AiFeature = "claude" | "research";

// Loaded once per page load and shared, since every AI section asks the same question.
let cached: Promise<AiStatus | null> | null = null;

function loadStatus(): Promise<AiStatus | null> {
  cached ??= apiFetch("/api/v1/ai/status")
    .then((response) => (response.ok ? (response.json() as Promise<AiStatus>) : null))
    .catch(() => null);
  return cached;
}

/** null while loading. */
export function useAiStatus(): AiStatus | null {
  const [status, setStatus] = useState<AiStatus | null>(null);
  useEffect(() => {
    let active = true;
    loadStatus().then((next) => {
      if (active) setStatus(next);
    });
    return () => {
      active = false;
    };
  }, []);
  return status;
}

export function aiReady(status: AiStatus | null, feature: AiFeature = "claude"): boolean {
  return status !== null && status[feature];
}

/** Why a feature is off, in words a visitor understands, with the setting that turns it on. */
function reason(status: AiStatus | null, feature: AiFeature): string {
  if (status === null) return "Checking whether AI is available…";
  if (!status.serviceConfigured) {
    return "This feature is built in, but AI is switched off on this server: no AI_SERVICE_TOKEN or Claude API key has been configured.";
  }
  if (!status.serviceReachable) return "This feature is built in, but the AI service isn't running right now.";
  if (!status.claude) return "This feature is built in, but no Claude API key (ANTHROPIC_API_KEY) has been configured on this server.";
  if (feature === "research" && !status.research) {
    return "Research is built in, but SEC_USER_AGENT isn't set, so filings can't be downloaded from SEC EDGAR.";
  }
  return "";
}

export function AiBadge({ status, feature = "claude" }: { status: AiStatus | null; feature?: AiFeature }) {
  const ready = aiReady(status, feature);
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium ${
        ready ? "bg-accent-soft text-accent" : "bg-background text-muted"
      }`}
    >
      {ready ? "AI ready" : "AI · not configured"}
    </span>
  );
}

/** Shown under a disabled AI button. Renders nothing once the feature is available. */
export function AiOffNote({ status, feature = "claude" }: { status: AiStatus | null; feature?: AiFeature }) {
  if (aiReady(status, feature) || status === null) return null;
  return <p className="rounded-lg border border-border bg-background px-3 py-2 text-xs text-muted">{reason(status, feature)}</p>;
}
