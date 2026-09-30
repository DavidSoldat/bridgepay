import { Component, computed, input, signal } from '@angular/core';
import { CHART, bandCenter, bandCenterPercent, bandWidth, formatValue, linearScale, niceTicks } from '../scale';
import { observeWidth } from '../observe-width';

export interface StackSeries {
  label: string;
  tone: 'approved' | 'declined' | 'review';
}

export interface StackBucket {
  label: string;
  values: number[]; // index-aligned with series
}

const FILL: Record<StackSeries['tone'], string> = {
  approved: 'fill-approved',
  declined: 'fill-declined',
  review: 'fill-review',
};
const SWATCH: Record<StackSeries['tone'], string> = {
  approved: 'bg-approved',
  declined: 'bg-declined',
  review: 'bg-review',
};

/** Counts per bucket stacked by series, with the same crosshair, keyboard and hidden table as TimeChart. */
@Component({
  selector: 'app-stacked-bars',
  templateUrl: './stacked-bars.html',
  host: { class: 'relative block' },
})
export class StackedBars {
  private static nextId = 0;

  buckets = input.required<StackBucket[]>();
  series = input.required<StackSeries[]>();
  title = input.required<string>();
  /** Tooltip heading per bucket (e.g. "Tue, Sep 29"); falls back to the axis label. */
  tooltipLabels = input<string[]>([]);

  protected readonly titleId = `stacked-bars-title-${StackedBars.nextId++}`;
  protected readonly C = CHART;
  protected readonly fill = FILL;
  protected readonly swatch = SWATCH;
  protected readonly activeIndex = signal<number | null>(null);
  protected readonly width = signal<number>(CHART.width);

  constructor() {
    observeWidth(this.width);
  }

  protected readonly baseline = CHART.height - CHART.padBottom;
  protected readonly totals = computed(() => this.buckets().map((b) => b.values.reduce((sum, v) => sum + v, 0)));
  protected readonly ticks = computed(() => niceTicks(Math.max(0, ...this.totals()), 4, true));
  protected readonly y = computed(() => linearScale([0, this.ticks().at(-1) || 1], [this.baseline, CHART.padTop]));
  protected readonly band = computed(() => bandWidth(this.buckets().length, this.width()));
  protected readonly xs = computed(() =>
    this.buckets().map((_, i) => bandCenter(i, this.buckets().length, this.width())),
  );
  protected readonly segments = computed(() => {
    const barWidth = Math.max(2, Math.min(24, this.band() * 0.6));
    const y = this.y();
    return this.buckets().flatMap((bucket, i) => {
      let below = 0;
      return bucket.values.flatMap((value, s) => {
        if (value <= 0) return [];
        const bottom = y(below);
        const top = y(below + value);
        const gap = below > 0 ? 1 : 0; // 1px between stacked segments
        below += value;
        return [{ i, s, x: this.xs()[i] - barWidth / 2, y: top, width: barWidth, height: Math.max(0, bottom - top - gap) }];
      });
    });
  });
  protected readonly labelEvery = computed(() => Math.max(1, Math.ceil(this.buckets().length / 6)));
  protected readonly tooltip = computed(() => {
    const i = this.activeIndex();
    const bucket = i === null ? undefined : this.buckets()[i];
    if (i === null || !bucket) return null;
    return {
      left: bandCenterPercent(i, this.buckets().length, this.width()),
      label: this.tooltipLabels()[i] ?? bucket.label,
      rows: this.series().map((s, k) => ({ label: s.label, value: formatValue(bucket.values[k] ?? 0, 'count') })),
      total: formatValue(this.totals()[i], 'count'),
    };
  });

  protected fmt(value: number): string {
    return formatValue(value, 'count');
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.buckets().length;
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
