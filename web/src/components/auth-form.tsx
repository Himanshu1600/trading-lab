"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState, type FormEvent } from "react";

import { useAuth } from "@/lib/auth";

const COPY = {
  login: {
    title: "Welcome back",
    subtitle: "Log in to your paper-trading account.",
    submit: "Log in",
    prompt: "No account yet?",
    linkHref: "/register",
    linkText: "Sign up",
  },
  register: {
    title: "Create your account",
    subtitle: "Start practising with paper money.",
    submit: "Sign up",
    prompt: "Already have an account?",
    linkHref: "/login",
    linkText: "Log in",
  },
};

function PasswordField({ isNew }: { isNew: boolean }) {
  const id = useId();
  const [visible, setVisible] = useState(false);

  return (
    <div className="flex flex-col gap-1.5 text-sm font-medium">
      <label htmlFor={id}>Password</label>
      <div className="relative">
        <input
          id={id}
          name="password"
          type={visible ? "text" : "password"}
          required
          minLength={isNew ? 8 : undefined}
          autoComplete={isNew ? "new-password" : "current-password"}
          className="input pr-16 font-normal"
        />
        <button
          type="button"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? "Hide password" : "Show password"}
          className="btn btn-sm btn-ghost absolute top-1/2 right-1.5 -translate-y-1/2"
        >
          {visible ? "Hide" : "Show"}
        </button>
      </div>
      {isNew && (
        <span className="text-xs font-normal text-muted">
          At least 8 characters.
        </span>
      )}
    </div>
  );
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const { signIn } = useAuth();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const copy = COPY[mode];

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError(null);
    setSubmitting(true);
    try {
      await signIn(mode, {
        ...(mode === "register" && { name: String(form.get("name")) }),
        email: String(form.get("email")),
        password: String(form.get("password")),
      });
      router.push("/");
    } catch (err) {
      setError(
        err instanceof TypeError
          ? "Can't reach the server. Is core-service running?"
          : (err as Error).message,
      );
      setSubmitting(false);
    }
  }

  return (
    <main className="flex flex-1 items-center justify-center px-4 py-12">
      <form
        onSubmit={handleSubmit}
        className="card flex w-full max-w-sm flex-col gap-4 p-6"
      >
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{copy.title}</h1>
          <p className="mt-1 text-sm text-muted">{copy.subtitle}</p>
        </div>
        {mode === "register" && (
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            Name
            <input
              name="name"
              required
              maxLength={50}
              autoComplete="name"
              placeholder="Your name"
              className="input font-normal"
            />
          </label>
        )}
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          Email
          <input
            name="email"
            type="email"
            required
            autoComplete="email"
            placeholder="you@example.com"
            className="input font-normal"
          />
        </label>
        <PasswordField isNew={mode === "register"} />
        {error && (
          <p role="alert" className="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting} className="btn btn-primary">
          {submitting ? "Please wait…" : copy.submit}
        </button>
        <p className="text-center text-sm text-muted">
          {copy.prompt}{" "}
          <Link href={copy.linkHref} className="font-medium text-accent hover:underline">
            {copy.linkText}
          </Link>
        </p>
      </form>
    </main>
  );
}
