import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { PercentPipe, formatDate } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { Applications } from '../applications';
import { OpsDashboard, OpsPeriodTotals } from '../../shared/models/ops-dashboard';
import { Change, changeClass, parseDays, pointsChange, relativeChange } from '../../shared/charts/change';
import { localDate } from '../../shared/charts/local-date';
import { Sparkline } from '../../shared/charts/sparkline/sparkline';
import { StackBucket, StackSeries, StackedBars } from '../../shared/charts/stacked-bars/stacked-bars';
import { ChartMarker, ChartPoint, TimeChart } from '../../shared/charts/time-chart/time-chart';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { formatDuration } from './duration';

const MIX_SERIES: StackSeries[] = [
  { label: 'Auto-approved', tone: 'approved' },
  { label: 'Auto-declined', tone: 'declined' },
  { label: 'Sent to review', tone: 'review' },
];

/** Decision engine thresholds (credit-risk-engine): below 0.3 approve, 0.3–0.7 review, above decline. */
const MARKERS: ChartMarker[] = [
  { at: 3, label: 'Review' },
  { at: 7, label: 'Decline' },
];

const autoRate = (t: OpsPeriodTotals) => (t.applications ? t.autoDecided / t.applications : null);

@Component({
  selector: 'app-ops-dashboard',
  imports: [PercentPipe, RouterLink, Sparkline, StackedBars, TimeChart, EmptyState],
  templateUrl: './ops-dashboard.html',
})
export class OpsDashboardPage {
  private readonly applications = inject(Applications);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  protected readonly periods = [7, 30, 90] as const;
  protected readonly mixSeries = MIX_SERIES;
  protected readonly markers = MARKERS;
  protected readonly changeClass = changeClass;
  protected readonly formatDuration = formatDuration;
  protected readonly autoRate = autoRate;

  protected readonly days = toSignal(this.route.queryParamMap.pipe(map((p) => parseDays(p.get('days')))), {
    initialValue: 30 as const,
  });
  protected readonly error = signal(false);

  /** switchMap drops a slower answer for a period the user has already left. */
  protected readonly dashboard = toSignal(
    toObservable(this.days).pipe(
      switchMap((days) => {
        this.error.set(false);
        return this.applications.dashboard(days, this.tz).pipe(
          catchError(() => {
            this.error.set(true);
            return of(null);
          }),
          startWith(null),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly queueAge = computed(() => {
    const oldest = this.dashboard()?.queue.oldestSubmittedAt;
    return oldest ? formatDuration((Date.now() - Date.parse(oldest)) / 1000) : '';
  });

  protected readonly applicationsChange = computed(() =>
    this.changeOf((d) => relativeChange(d.current.applications, d.previous.applications, d.days)),
  );
  protected readonly autoChange = computed(() =>
    this.changeOf((d) => pointsChange(autoRate(d.current), autoRate(d.previous), d.days)),
  );
  protected readonly rateChange = computed(() =>
    this.changeOf((d) => pointsChange(d.current.approvalRate, d.previous.approvalRate, d.days)),
  );
  /** Only compare two real medians: one missing would read as a 100% change. */
  protected readonly reviewTimeChange = computed(() =>
    this.changeOf((d) => {
      const [now, before] = [d.current.medianReviewSeconds, d.previous.medianReviewSeconds];
      return now === null || before === null ? relativeChange(0, 0, d.days) : relativeChange(now, before, d.days);
    }),
  );

  protected readonly applicationsTrend = computed(
    () => this.dashboard()?.series.map((p) => p.autoApproved + p.autoDeclined + p.review) ?? [],
  );
  protected readonly mixBuckets = computed<StackBucket[]>(
    () => this.dashboard()?.series.map((p) => ({
      label: formatDate(localDate(p.start), 'MMM d', 'en-US'),
      values: [p.autoApproved, p.autoDeclined, p.review],
    })) ?? [],
  );
  protected readonly mixTooltips = computed(() => {
    const d = this.dashboard();
    if (!d) return [];
    return d.series.map((p) => d.bucket === 'WEEK'
      ? `Week of ${formatDate(localDate(p.start), 'MMM d', 'en-US')}`
      : formatDate(localDate(p.start), 'EEE, MMM d', 'en-US'));
  });
  protected readonly histogram = computed<ChartPoint[]>(
    () => this.dashboard()?.scoreHistogram.map((b) => ({ label: `${b.from.toFixed(1)}–${b.to.toFixed(1)}`, value: b.count })) ?? [],
  );
  protected readonly bands = computed(() => {
    const counts = this.dashboard()?.scoreHistogram.map((b) => b.count) ?? [];
    const sum = (from: number, to: number) => counts.slice(from, to).reduce((a, n) => a + n, 0);
    return { approve: sum(0, 3), review: sum(3, 7), decline: sum(7, 10) };
  });

  protected selectDays(days: number): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: { days }, queryParamsHandling: 'merge' });
  }

  private changeOf(fn: (d: OpsDashboard) => Change): Change | null {
    const d = this.dashboard();
    return d ? fn(d) : null;
  }
}
