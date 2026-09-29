import { Component, computed, input, model, signal } from '@angular/core';
import {
  CHART, areaPath, bandCenter, bandWidth, formatValue, linePath, linearScale, niceTicks, roundedBarPath,
} from '../scale';
import { observeWidth } from '../observe-width';

export interface ChartPoint {
  label: string;
  value: number;
}

/** One single-series panel over time. Two panels can share activeIndex for a common crosshair. */
@Component({
  selector: 'app-time-chart',
  templateUrl: './time-chart.html',
  host: { class: 'block' },
})
export class TimeChart {
  points = input.required<ChartPoint[]>();
  title = input.required<string>();
  kind = input<'area' | 'bar'>('area');
  format = input<'money' | 'count'>('count');
  activeIndex = model<number | null>(null);

  protected readonly C = CHART;
  /** Rendered width in CSS pixels, used as the viewBox width so text and strokes are never scaled down. */
  protected readonly width = signal<number>(CHART.width);

  constructor() {
    observeWidth(this.width);
  }

  protected readonly baseline = CHART.height - CHART.padBottom;
  protected readonly ticks = computed(() =>
    niceTicks(Math.max(0, ...this.points().map((p) => p.value)), 4, this.format() === 'count'),
  );
  protected readonly y = computed(() => linearScale([0, this.ticks().at(-1) || 1], [this.baseline, CHART.padTop]));
  protected readonly band = computed(() => bandWidth(this.points().length, this.width()));
  protected readonly xs = computed(() => this.points().map((_, i) => bandCenter(i, this.points().length, this.width())));
  private readonly coords = computed(() =>
    this.points().map((p, i) => [this.xs()[i], this.y()(p.value)] as [number, number]),
  );
  protected readonly line = computed(() => linePath(this.coords()));
  protected readonly area = computed(() => areaPath(this.coords(), this.baseline));
  protected readonly bars = computed(() => {
    const width = Math.max(2, Math.min(24, this.band() * 0.6));
    return this.coords()
      .map(([x, top], i) => ({ i, d: roundedBarPath(x - width / 2, width, top, this.baseline) }))
      .filter((bar) => bar.d);
  });
  /** Show about six x labels whatever the number of points. */
  protected readonly labelEvery = computed(() => Math.max(1, Math.ceil(this.points().length / 6)));

  protected fmt(value: number, precise = false): string {
    return formatValue(value, this.format(), precise);
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.points().length;
    if (!n) return;
    const i = this.activeIndex();
    if (event.key === 'ArrowRight') {
      this.activeIndex.set(i === null ? 0 : Math.min(n - 1, i + 1));
      event.preventDefault();
    } else if (event.key === 'ArrowLeft') {
      this.activeIndex.set(i === null ? n - 1 : Math.max(0, i - 1));
      event.preventDefault();
    } else if (event.key === 'Escape') {
      this.activeIndex.set(null);
    }
  }
}
