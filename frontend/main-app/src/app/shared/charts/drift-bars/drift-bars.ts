import { Component, computed, input } from '@angular/core';
import { StatusBadge } from '../../ui/status-badge/status-badge';

export interface DriftRow {
  feature: string;
  label: string;
  /** Mean log-odds contribution; 0 is the training average. */
  mean: number;
  shifted: boolean;
}

/**
 * Diverging bars around zero, one row per feature: coral pushes risk up, cool pushes it down. The ±band is
 * shaded; the scale is symmetric and always shows the whole band. Rows are labelled with their value directly.
 */
@Component({
  selector: 'app-drift-bars',
  imports: [StatusBadge],
  templateUrl: './drift-bars.html',
})
export class DriftBars {
  private static nextId = 0;

  rows = input.required<DriftRow[]>();
  band = input.required<number>();
  title = input.required<string>();

  protected readonly titleId = `drift-bars-title-${DriftBars.nextId++}`;
  /** Half the track spans this much log-odds: the largest |mean|, never less than the band. */
  private readonly extent = computed(() => Math.max(this.band(), ...this.rows().map((r) => Math.abs(r.mean))));
  protected readonly bandWidthPct = computed(() => (this.band() / this.extent()) * 100);
  protected readonly bars = computed(() =>
    this.rows().map((r) => ({
      ...r,
      width: (Math.abs(r.mean) / this.extent()) * 50,
      text: (r.mean >= 0 ? '+' : '−') + Math.abs(r.mean).toFixed(2),
    })),
  );
}
