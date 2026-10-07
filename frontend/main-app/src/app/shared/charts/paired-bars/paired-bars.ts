import { Component, computed, input, signal } from '@angular/core';
import { CHART, bandCenter, bandCenterPercent, bandWidth, linearScale, niceTicks, roundedBarPath } from '../scale';
import { observeWidth } from '../observe-width';
import { ChartMarker } from '../time-chart/time-chart';

/** Two shares (0–1) for one category: the reference and this period. */
export interface PairedRow {
  label: string;
  training: number;
  current: number;
}

const percent = (v: number) => `${Math.round(v * 100)}%`;

/** Reference vs current share per category, side by side, with the same keyboard/tooltip/table as StackedBars. */
@Component({
  selector: 'app-paired-bars',
  templateUrl: './paired-bars.html',
  host: { class: 'relative block' },
})
export class PairedBars {
  private static nextId = 0;

  rows = input.required<PairedRow[]>();
  title = input.required<string>();
  /** Dashed lines at category boundaries: at 3 sits between the 3rd and 4th category. */
  markers = input<ChartMarker[]>([]);

  protected readonly titleId = `paired-bars-title-${PairedBars.nextId++}`;
  protected readonly C = CHART;
  protected readonly activeIndex = signal<number | null>(null);
  protected readonly width = signal<number>(CHART.width);
  protected readonly baseline = CHART.height - CHART.padBottom;
  protected readonly pct = percent;

  constructor() {
    observeWidth(this.width);
  }

  protected readonly ticks = computed(() => niceTicks(Math.max(0, ...this.rows().flatMap((r) => [r.training, r.current]))));
  protected readonly y = computed(() => linearScale([0, this.ticks().at(-1) || 1], [this.baseline, CHART.padTop]));
  protected readonly band = computed(() => bandWidth(this.rows().length, this.width()));
  protected readonly xs = computed(() => this.rows().map((_, i) => bandCenter(i, this.rows().length, this.width())));
  /** Two bars per category with a 2px surface gap between them. */
  protected readonly bars = computed(() => {
    const w = Math.max(2, Math.min(14, this.band() * 0.3));
    const y = this.y();
    return this.rows().map((r, i) => ({
      training: roundedBarPath(this.xs()[i] - 1 - w, w, y(r.training), this.baseline),
      current: roundedBarPath(this.xs()[i] + 1, w, y(r.current), this.baseline),
    }));
  });
  protected readonly markerXs = computed(() =>
    this.markers().map((m) => ({ ...m, x: CHART.padLeft + this.band() * m.at })),
  );
  protected readonly labelEvery = computed(() => Math.max(1, Math.ceil(this.rows().length / (this.width() < 420 ? 5 : 10))));
  protected readonly tooltip = computed(() => {
    const i = this.activeIndex();
    const row = i === null ? undefined : this.rows()[i];
    if (i === null || !row) return null;
    return { left: bandCenterPercent(i, this.rows().length, this.width()), row };
  });

  protected onKey(event: KeyboardEvent): void {
    const n = this.rows().length;
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
