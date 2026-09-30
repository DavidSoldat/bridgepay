import { Component, computed, input, signal } from '@angular/core';
import { formatDate } from '@angular/common';
import { TimeChart, ChartPoint } from '../time-chart/time-chart';
import { CHART, bandCenterPercent, formatValue } from '../scale';
import { observeWidth } from '../observe-width';
import { localDate } from '../local-date';
import { SeriesPoint } from '../../models/merchant-dashboard';

@Component({
  selector: 'app-sales-over-time',
  imports: [TimeChart],
  templateUrl: './sales-over-time.html',
  host: { class: 'block' },
})
export class SalesOverTime {
  series = input.required<SeriesPoint[]>();
  bucket = input.required<'DAY' | 'WEEK'>();

  protected readonly active = signal<number | null>(null);
  /** Same rendered width as the two panels inside, so the tooltip lines up with their buckets. */
  private readonly width = signal<number>(CHART.width);

  constructor() {
    observeWidth(this.width);
  }

  private readonly axisLabels = computed(() =>
    this.series().map((p) => formatDate(localDate(p.start), 'MMM d', 'en-US')),
  );
  protected readonly volumePoints = computed<ChartPoint[]>(() =>
    this.series().map((p, i) => ({ label: this.axisLabels()[i], value: p.approvedVolume })),
  );
  protected readonly checkoutPoints = computed<ChartPoint[]>(() =>
    this.series().map((p, i) => ({ label: this.axisLabels()[i], value: p.checkouts })),
  );
  protected readonly tooltip = computed(() => {
    const i = this.active();
    const point = i === null ? undefined : this.series()[i];
    if (i === null || !point) return null;
    const date = localDate(point.start);
    return {
      left: bandCenterPercent(i, this.series().length, this.width()),
      label: this.bucket() === 'WEEK' ? `Week of ${formatDate(date, 'MMM d', 'en-US')}` : formatDate(date, 'EEE, MMM d', 'en-US'),
      volume: formatValue(point.approvedVolume, 'money', true),
      checkouts: formatValue(point.checkouts, 'count'),
    };
  });
}
