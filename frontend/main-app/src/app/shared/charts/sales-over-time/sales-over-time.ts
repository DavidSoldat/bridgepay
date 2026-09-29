import { Component, computed, input, signal } from '@angular/core';
import { formatDate } from '@angular/common';
import { TimeChart, ChartPoint } from '../time-chart/time-chart';
import { bandCenterPercent, formatValue } from '../scale';
import { SeriesPoint } from '../../models/merchant-dashboard';

/** yyyy-MM-dd as a local date (new Date('2026-09-29') would be UTC midnight and can show the day before). */
function localDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

@Component({
  selector: 'app-sales-over-time',
  imports: [TimeChart],
  templateUrl: './sales-over-time.html',
})
export class SalesOverTime {
  series = input.required<SeriesPoint[]>();
  bucket = input.required<'DAY' | 'WEEK'>();

  protected readonly active = signal<number | null>(null);

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
      left: bandCenterPercent(i, this.series().length),
      label: this.bucket() === 'WEEK' ? `Week of ${formatDate(date, 'MMM d', 'en-US')}` : formatDate(date, 'EEE, MMM d', 'en-US'),
      volume: formatValue(point.approvedVolume, 'money', true),
      checkouts: formatValue(point.checkouts, 'count'),
    };
  });
}
