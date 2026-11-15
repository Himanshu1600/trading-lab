/** Types and helpers shared by the Strategies and Backtests pages. */

export type SavedStrategy = {
  id: string;
  name: string;
  dsl: Record<string, unknown>;
  sourcePrompt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Metrics = {
  startEquity: number;
  endEquity: number;
  totalReturnPct: number;
  cagrPct: number;
  sharpe: number;
  maxDrawdownPct: number;
  tradingDays: number;
  trades: number | null;
  winRatePct: number | null;
  profitFactor: number | null;
  exposurePct: number | null;
};

export type RunStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";

export type BacktestRun = {
  id: string;
  strategyId: string | null;
  strategyName: string;
  status: RunStatus;
  params: {
    symbols: string[];
    from: string;
    to: string;
    initialCash: number;
    feeBps: number;
    slippageBps: number;
  };
  metrics: { strategy: Metrics; benchmark: Metrics } | null;
  error: string | null;
  createdAt: string;
  durationMs: number | null;
  dsl?: Record<string, unknown>;
  inputHash?: string;
  explanation?: string | null;
};

export type BacktestTrade = {
  symbol: string;
  qty: number;
  entryDate: string;
  entryPrice: number;
  exitDate: string;
  exitPrice: number;
  pnl: number;
  returnPct: number;
  exitReason: "SIGNAL" | "STOP_LOSS" | "TAKE_PROFIT" | "END_OF_TEST";
};

export type EquityPoint = {
  date: string;
  equity: number;
  drawdown: number;
  benchmark: number;
};

export function isFinished(status: RunStatus): boolean {
  return status === "COMPLETED" || status === "FAILED";
}

export function formatPercent(value: number | null | undefined, signed = false): string {
  if (value === null || value === undefined) return "—";
  return `${signed && value > 0 ? "+" : ""}${value.toFixed(2)}%`;
}

export const EXIT_REASONS: Record<BacktestTrade["exitReason"], string> = {
  SIGNAL: "Exit rule",
  STOP_LOSS: "Stop-loss",
  TAKE_PROFIT: "Take-profit",
  END_OF_TEST: "Still open at the end",
};

/** Starting points for the editor. The same examples live in contracts/strategy-dsl.v1.schema.json. */
export const TEMPLATES: { label: string; dsl: Record<string, unknown> }[] = [
  {
    label: "Moving-average crossover",
    dsl: {
      dslVersion: 1,
      name: "20/50 SMA crossover",
      symbols: ["AAPL"],
      timeframe: "1D",
      entry: {
        crossesAbove: [
          { indicator: "SMA", period: 20 },
          { indicator: "SMA", period: 50 },
        ],
      },
      exit: {
        crossesBelow: [
          { indicator: "SMA", period: 20 },
          { indicator: "SMA", period: 50 },
        ],
      },
      risk: { stopLossPct: 5, takeProfitPct: 15 },
      sizing: { type: "percentOfEquity", value: 100 },
    },
  },
  {
    label: "RSI dip in an uptrend",
    dsl: {
      dslVersion: 1,
      name: "RSI dip buyer above the 200-day average",
      symbols: ["MSFT", "NVDA"],
      entry: {
        all: [
          { lt: [{ indicator: "RSI", period: 14 }, 30] },
          { gt: [{ price: "close" }, { indicator: "SMA", period: 200 }] },
        ],
      },
      exit: { gt: [{ indicator: "RSI", period: 14 }, 70] },
      risk: { stopLossPct: 8 },
      sizing: { type: "percentOfEquity", value: 50, maxPositions: 2 },
    },
  },
  {
    label: "MACD momentum",
    dsl: {
      dslVersion: 1,
      name: "MACD momentum with a Bollinger exit",
      symbols: ["GOOGL"],
      entry: {
        crossesAbove: [
          { indicator: "MACD", output: "macd" },
          { indicator: "MACD", output: "signal" },
        ],
      },
      exit: {
        gt: [
          { price: "close" },
          { indicator: "BOLLINGER", period: 20, stdDev: 2, output: "upper" },
        ],
      },
      sizing: { type: "fixedCash", value: 25000 },
    },
  },
];
