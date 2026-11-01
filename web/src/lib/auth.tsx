"use client";

import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type User = { id: string; name: string; email: string };
type AuthResponse = { accessToken: string; expiresIn: number; user: User };

// Kept in memory only, so injected scripts can't read it from storage. The
// httpOnly refresh cookie restores it after a page load.
let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;

// Shared by concurrent callers: the server rotates the token on each refresh.
function refreshSession(): Promise<boolean> {
  refreshing ??= fetch(`${API_URL}/api/v1/auth/refresh`, {
    method: "POST",
    credentials: "include",
  })
    .then(async (response) => {
      const body: AuthResponse | null = response.ok
        ? await response.json()
        : null;
      accessToken = body?.accessToken ?? null;
      return accessToken !== null;
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

/** Calls core-service as the current user, refreshing an expired token once. */
export async function apiFetch(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  if (!accessToken && !(await refreshSession())) {
    return new Response(null, { status: 401 });
  }
  const send = () => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${accessToken}`);
    return fetch(`${API_URL}${path}`, { ...init, headers });
  };
  const response = await send();
  if (response.status === 401 && (await refreshSession())) {
    return send();
  }
  return response;
}

type Credentials = { name?: string; email: string; password: string };

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  signIn: (mode: "login" | "register", credentials: Credentials) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch("/api/v1/me")
      .then(async (response) => setUser(response.ok ? await response.json() : null))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      loading,
      async signIn(mode, credentials) {
        const response = await fetch(`${API_URL}/api/v1/auth/${mode}`, {
          method: "POST",
          credentials: "include",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(credentials),
        });
        if (!response.ok) {
          const problem = await response.json().catch(() => null);
          throw new Error(
            response.status === 400
              ? "Check your details. Passwords need 8 to 72 characters."
              : (problem?.detail ?? "Something went wrong. Try again."),
          );
        }
        const body: AuthResponse = await response.json();
        accessToken = body.accessToken;
        setUser(body.user);
      },
      async signOut() {
        await fetch(`${API_URL}/api/v1/auth/logout`, {
          method: "POST",
          credentials: "include",
        });
        accessToken = null;
        setUser(null);
      },
    }),
    [user, loading],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
