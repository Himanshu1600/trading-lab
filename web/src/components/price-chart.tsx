"use client";

import {
  CandlestickSeries,
  ColorType,
  createChart,
  HistogramSeries,
  type CandlestickData,
  type ChartOptions,
  type DeepPartial,
  type IChartApi,
  type ISeriesApi,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";

import { apiFetch } from "@/lib/auth";
import { formatMoney, useMarketStatus, useQuotes } from "@/lib/market";

type ApiBar = {
  time: string;
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

type Bar = { time: Time; open: number; high: number; low: number; close: number };

const RANGES = ["1D", "5D", "1M", "3M", "6M", "1Y", "5Y"] as const;
type Range = (typeof RANGES)[number];

const INTRADAY: Range[] = ["1D", "5D"];
const BAR_SECONDS = 300;
// Intraday history refreshes on the server once a minute, so the chart re-reads it as often.
const INTRADAY_REFETCH_MS = 60_000;
const MIN_VISIBLE_BARS = 10;

type Loaded = { key: string; bars: ApiBar[]; failed: boolean };

// The chart draws on a canvas, so it can't use CSS classes. It reads the same colour variables instead.
function theme() {
  const styles = getComputedStyle(document.documentElement);
  const color = (name: string) => styles.getPropertyValue(name).trim();
  return {
    surface: color("--surface"),
    muted: color("--muted"),
    border: color("--border"),
    up: color("--accent"),
    down: color("--danger"),
    font: getComputedStyle(document.body).fontFamily,
  };
}

function chartOptions(): DeepPartial<ChartOptions> {
  const colors = theme();
  return {
    layout: {
      background: { type: ColorType.Solid, color: colors.surface },
      textColor: colors.muted,
      fontFamily: colors.font,
    },
    grid: {
      vertLines: { color: colors.border },
      horzLines: { color: colors.border },
    },
  };
}

function seriesColors() {
  const colors = theme();
  return {
    upColor: colors.up,
    downColor: colors.down,
    wickUpColor: colors.up,
    wickDownColor: colors.down,
  };
}

const newYorkParts = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/New_York",
  hourCycle: "h23",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/**
 * The chart labels times as if they were UTC. Shifting each timestamp by New York's offset
 * makes intraday bars read in market time, so 9:30 means the opening bell.
 */
function marketTime(epochSeconds: number): UTCTimestamp {
  const parts = Object.fromEntries(
    newYorkParts.formatToParts(new Date(epochSeconds * 1000)).map((part) => [part.type, part.value]),
  );
  return (Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  ) / 1000) as UTCTimestamp;
}

function marketDay(date: Date): string {
  const parts = Object.fromEntries(newYorkParts.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function toChartBar(bar: ApiBar, intraday: boolean): Bar {
  return {
    time: intraday ? marketTime(bar.timestamp) : bar.time,
    open: bar.open,
    high: bar.high,
    low: bar.low,
    close: bar.close,
  };
}

/**
 * The newest candle, moved by the live price. Built from the last loaded bar each time, so it
 * stays a pure calculation; the next refetch replaces it with the server's version.
 */
function liveBar(last: Bar | undefined, price: number, intraday: boolean, now: Date): Bar | null {
  if (!last || price <= 0) return null;
  const time: Time = intraday
    ? marketTime(Math.floor(now.getTime() / 1000 / BAR_SECONDS) * BAR_SECONDS)
    : marketDay(now);
  if (time < last.time) return null;
  if (time === last.time) {
    return {
      ...last,
      high: Math.max(last.high, price),
      low: Math.min(last.low, price),
      close: price,
    };
  }
  return {
    time,
    open: last.close,
    high: Math.max(last.close, price),
    low: Math.min(last.close, price),
    close: price,
  };
}

function sameBar(a: Bar | null, b: Bar | null): boolean {
  return (
    a === b ||
    (a !== null &&
      b !== null &&
      a.time === b.time &&
      a.open === b.open &&
      a.high === b.high &&
      a.low === b.low &&
      a.close === b.close)
  );
}

function subscribeFullscreen(onChange: () => void) {
  document.addEventListener("fullscreenchange", onChange);
  return () => document.removeEventListener("fullscreenchange", onChange);
}

function subscribeNothing() {
  return () => {};
}

export function PriceChart({ symbol }: { symbol: string }) {
  const wrapper = useRef<HTMLDivElement>(null);
  const container = useRef<HTMLDivElement>(null);
  const candles = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volume = useRef<ISeriesApi<"Histogram"> | null>(null);
  const chart = useRef<IChartApi | null>(null);
  const fittedKey = useRef<string | null>(null);
  const [range, setRange] = useState<Range>("1D");
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [refetch, setRefetch] = useState(0);
  const [hovered, setHovered] = useState<Bar | null>(null);
  const isFullscreen = useSyncExternalStore(
    subscribeFullscreen,
    () => document.fullscreenElement !== null && document.fullscreenElement === wrapper.current,
    () => false,
  );
  const canFullscreen = useSyncExternalStore(
    subscribeNothing,
    () => document.fullscreenEnabled,
    () => false,
  );
  const market = useMarketStatus();
  const quote = useQuotes([symbol])[symbol];
  const intraday = INTRADAY.includes(range);
  const key = `${symbol}:${range}`;

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const created = createChart(element, {
      ...chartOptions(),
      autoSize: true,
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, rightOffset: 4 },
      handleScroll: true,
      handleScale: true,
    });
    const candleSeries = created.addSeries(CandlestickSeries, {
      ...seriesColors(),
      borderVisible: false,
    });
    candleSeries.priceScale().applyOptions({ scaleMargins: { top: 0.05, bottom: 0.25 } });
    const volumeSeries = created.addSeries(HistogramSeries, {
      priceScaleId: "volume",
      priceFormat: { type: "volume" },
      color: theme().border,
    });
    created.priceScale("volume").applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    // The chart also calls this when a live update redraws under a still mouse. Keeping the
    // previous state when nothing changed stops that from looping back into another update.
    created.subscribeCrosshairMove((param) => {
      const data = param.seriesData.get(candleSeries) as CandlestickData | undefined;
      const next = data && "open" in data ? (data as Bar) : null;
      setHovered((previous) => (sameBar(previous, next) ? previous : next));
    });
    chart.current = created;
    candles.current = candleSeries;
    volume.current = volumeSeries;

    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const applyTheme = () => {
      created.applyOptions(chartOptions());
      candleSeries.applyOptions(seriesColors());
      volumeSeries.applyOptions({ color: theme().border });
    };
    media.addEventListener("change", applyTheme);
    return () => {
      media.removeEventListener("change", applyTheme);
      created.remove();
      chart.current = null;
      candles.current = null;
      volume.current = null;
    };
  }, []);

  useEffect(() => {
    chart.current?.applyOptions({ timeScale: { timeVisible: intraday, secondsVisible: false } });
  }, [intraday]);

  useEffect(() => {
    if (!intraday) return;
    const timer = setInterval(() => setRefetch((count) => count + 1), INTRADAY_REFETCH_MS);
    return () => clearInterval(timer);
  }, [intraday]);

  useEffect(() => {
    let active = true;
    const [requestedSymbol, requestedRange] = key.split(":");
    apiFetch(
      `/api/v1/market/bars?symbol=${encodeURIComponent(requestedSymbol)}&range=${requestedRange}`,
    )
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then((bars: ApiBar[]) => {
        if (active) setLoaded({ key, bars, failed: false });
      })
      .catch(() => {
        // A failed background refresh keeps the chart that's already showing.
        if (active) setLoaded((previous) => (previous?.key === key ? previous : { key, bars: [], failed: true }));
      });
    return () => {
      active = false;
    };
  }, [key, refetch]);

  const current = loaded?.key === key ? loaded : null;
  const lastLoaded = useMemo(() => {
    const last = current?.bars.at(-1);
    return last ? toChartBar(last, intraday) : undefined;
  }, [current, intraday]);
  const marketOpen = market?.open ?? false;
  // Recomputed only when a new price arrives (every 5 seconds) or new bars load.
  const live = useMemo(
    () => (marketOpen && quote ? liveBar(lastLoaded, quote.last, intraday, new Date()) : null),
    [marketOpen, quote, lastLoaded, intraday],
  );

  useEffect(() => {
    if (!current) return;
    candles.current?.setData(current.bars.map((bar) => toChartBar(bar, intraday)));
    volume.current?.setData(
      current.bars.map((bar) => ({ time: toChartBar(bar, intraday).time, value: bar.volume })),
    );
    // Fit only when a new symbol or range arrives, so a background refresh keeps your zoom.
    if (fittedKey.current !== current.key) {
      chart.current?.timeScale().fitContent();
      fittedKey.current = current.key;
    }
  }, [current, intraday]);

  useEffect(() => {
    if (live) candles.current?.update(live);
  }, [live]);

  function zoom(factor: number) {
    const timeScale = chart.current?.timeScale();
    const visible = timeScale?.getVisibleLogicalRange();
    if (!timeScale || !visible) return;
    const span = Math.max((visible.to - visible.from) * factor, MIN_VISIBLE_BARS);
    timeScale.setVisibleLogicalRange({ from: visible.to - span, to: visible.to });
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      void wrapper.current?.requestFullscreen();
    }
  }

  let message: string | null = null;
  if (!current) message = "Loading chart…";
  else if (current.failed) message = "Couldn't load the price history.";
  else if (current.bars.length === 0) message = "No price history available.";

  const shown = hovered ?? live ?? lastLoaded;

  return (
    <div
      ref={wrapper}
      className={`flex flex-col gap-3 bg-surface ${isFullscreen ? "h-full p-4" : ""}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label="Chart range" className="flex flex-wrap gap-1">
          {RANGES.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={range === option}
              onClick={() => {
                setRange(option);
                setHovered(null);
              }}
              className="btn btn-sm btn-ghost aria-pressed:bg-accent-soft aria-pressed:text-accent"
            >
              {option}
            </button>
          ))}
        </div>
        <div className="ml-auto flex items-center gap-1">
          {live && (
            <span className="mr-1 flex items-center gap-1.5 text-xs font-medium text-accent">
              <span aria-hidden className="size-2 animate-pulse rounded-full bg-accent" />
              Live
            </span>
          )}
          <button type="button" onClick={() => zoom(0.7)} aria-label="Zoom in" className="btn btn-sm btn-secondary">
            +
          </button>
          <button type="button" onClick={() => zoom(1.4)} aria-label="Zoom out" className="btn btn-sm btn-secondary">
            −
          </button>
          <button
            type="button"
            onClick={() => chart.current?.timeScale().fitContent()}
            className="btn btn-sm btn-secondary"
          >
            Reset
          </button>
          {canFullscreen && (
            <button type="button" onClick={toggleFullscreen} className="btn btn-sm btn-secondary">
              {isFullscreen ? "Exit full screen" : "Full screen"}
            </button>
          )}
        </div>
      </div>

      <p className="min-h-5 font-mono text-xs text-muted" aria-live="off">
        {shown && (
          <>
            O {formatMoney(shown.open)} · H {formatMoney(shown.high)} · L {formatMoney(shown.low)} · C{" "}
            {formatMoney(shown.close)}
          </>
        )}
      </p>

      <div className={`relative w-full ${isFullscreen ? "min-h-0 flex-1" : "h-112"}`}>
        <div ref={container} className="absolute inset-0" />
        {message && (
          <p className="absolute inset-0 flex items-center justify-center bg-surface/80 text-sm text-muted">
            {message}
          </p>
        )}
      </div>

      <p className="text-xs text-muted">
        {intraday
          ? "5-minute bars in New York time, including pre-market and after-hours trading. Volume covers IEX only, a slice of the whole market."
          : "Daily bars, adjusted for splits, with full-market volume. History runs about 15 minutes behind."}{" "}
        Scroll or pinch to zoom, drag to move.{market?.open ? " The newest bar moves with the live price." : ""}
      </p>
    </div>
  );
}
