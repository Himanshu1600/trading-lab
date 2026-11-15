"use client";

import {
  AreaSeries,
  ColorType,
  createChart,
  LineSeries,
  type IChartApi,
} from "lightweight-charts";
import { useEffect, useRef } from "react";

import type { EquityPoint } from "@/lib/backtests";

// The chart draws on a canvas, so it reads the page's colour variables instead of CSS classes.
function theme() {
  const styles = getComputedStyle(document.documentElement);
  const color = (name: string) => styles.getPropertyValue(name).trim();
  return {
    surface: color("--surface"),
    muted: color("--muted"),
    border: color("--border"),
    accent: color("--accent"),
    danger: color("--danger"),
    font: getComputedStyle(document.body).fontFamily,
  };
}

/**
 * The strategy's equity against buy-and-hold on top, and its drawdown below, one point per
 * trading day. Redraws when the system switches between light and dark mode.
 */
export function EquityChart({ points }: { points: EquityPoint[] }) {
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = container.current;
    if (!element || points.length === 0) return;
    let chart: IChartApi | null = null;

    function draw() {
      chart?.remove();
      const colors = theme();
      chart = createChart(element!, {
        autoSize: true,
        layout: {
          background: { type: ColorType.Solid, color: colors.surface },
          textColor: colors.muted,
          fontFamily: colors.font,
          panes: { separatorColor: colors.border },
        },
        grid: {
          vertLines: { color: colors.border },
          horzLines: { color: colors.border },
        },
        rightPriceScale: { borderColor: colors.border },
        timeScale: { borderColor: colors.border },
      });
      chart
        .addSeries(LineSeries, { color: colors.muted, lineWidth: 1, title: "Buy and hold" })
        .setData(points.map((point) => ({ time: point.date, value: point.benchmark })));
      chart
        .addSeries(LineSeries, { color: colors.accent, lineWidth: 2, title: "Strategy" })
        .setData(points.map((point) => ({ time: point.date, value: point.equity })));
      const drawdown = chart.addSeries(
        AreaSeries,
        {
          lineColor: colors.danger,
          topColor: colors.danger,
          bottomColor: "transparent",
          lineWidth: 1,
          title: "Drawdown %",
          priceFormat: { type: "custom", formatter: (value: number) => `${value.toFixed(1)}%` },
        },
        1,
      );
      drawdown.setData(points.map((point) => ({ time: point.date, value: -point.drawdown * 100 })));
      chart.panes()[1]?.setHeight(110);
      chart.timeScale().fitContent();
    }

    draw();
    const scheme = window.matchMedia("(prefers-color-scheme: dark)");
    scheme.addEventListener("change", draw);
    return () => {
      scheme.removeEventListener("change", draw);
      chart?.remove();
    };
  }, [points]);

  return <div ref={container} className="h-96 w-full" aria-label="Equity and drawdown chart" />;
}
