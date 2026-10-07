import { Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { map, switchMap } from 'rxjs';
import { ModelApi } from '../model-api';
import { Section, toSection } from '../../shared/models/section';
import { DRIFT_BAND, MIN_FINISHED, MIN_SCORED, ModelBaseline, ModelMonitoring } from '../../shared/models/model-monitoring';
import { featureLabel } from '../../shared/models/feature-labels';
import { psi, psiStatus } from './psi';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { PairedBars } from '../../shared/charts/paired-bars/paired-bars';
import { DriftBars } from '../../shared/charts/drift-bars/drift-bars';

const PERIODS = [7, 30, 90] as const;
const DEFAULT_DAYS = 30;
const FEW = 20;

function bandLabel(bin: { from: number; to: number }): string {
  return `${bin.from.toFixed(1)}–${bin.to.toFixed(1)}`;
}

/**
 * Model health: drift of this period's population against the training data, and realized outcomes per score
 * band against the training evaluation set. Training facts come from credit-risk-engine, production facts from
 * application-service; each loads and fails on its own.
 */
@Component({
  selector: 'app-model-page',
  imports: [DecimalPipe, PercentPipe, StatusBadge, SkeletonRows, PairedBars, DriftBars],
  templateUrl: './model-page.html',
})
export class ModelPage {
  private readonly api = inject(ModelApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly periods = PERIODS;
  protected readonly days = toSignal(
    this.route.queryParamMap.pipe(map((p) => {
      const d = Number(p.get('days'));
      return (PERIODS as readonly number[]).includes(d) ? d : DEFAULT_DAYS;
    })),
    { initialValue: DEFAULT_DAYS },
  );
  protected readonly baseline = toSignal(toSection(this.api.baseline()), {
    initialValue: { state: 'loading' } as Section<ModelBaseline>,
  });
  protected readonly monitoring = toSignal(
    toObservable(this.days).pipe(switchMap((d) => toSection(this.api.monitoring(d)))),
    { initialValue: { state: 'loading' } as Section<ModelMonitoring> },
  );

  protected readonly minScored = MIN_SCORED;
  protected readonly minFinished = MIN_FINISHED;
  protected readonly label = featureLabel;
  protected readonly driftBand = DRIFT_BAND;

  private readonly both = computed(() => {
    const b = this.baseline();
    const m = this.monitoring();
    return b.state === 'ready' && m.state === 'ready' ? { b: b.value, m: m.value } : null;
  });

  protected readonly scored = computed(() => {
    const m = this.monitoring();
    return m.state === 'ready' ? m.value.drift.scored : null;
  });
  protected readonly enoughScored = computed(() => (this.scored() ?? 0) >= MIN_SCORED);

  protected readonly psiValue = computed(() => {
    const x = this.both();
    return x ? psi(x.m.drift.scoreBins.map((b) => b.count), x.b.scoreBins.map((b) => b.share)) : null;
  });
  protected readonly psiBadge = computed(() => {
    const v = this.psiValue();
    return v === null ? null : psiStatus(v);
  });

  /** Training share vs this period's share per score bin. */
  protected readonly distribution = computed(() => {
    const x = this.both();
    if (!x) return [];
    const total = x.m.drift.scored || 1;
    return x.m.drift.scoreBins.map((bin, i) => ({
      label: bandLabel(bin),
      training: x.b.scoreBins[i]?.share ?? 0,
      current: bin.count / total,
    }));
  });

  /** One row per model feature in the baseline's order; a feature never seen this period reads 0. */
  protected readonly drift = computed(() => {
    const x = this.both();
    if (!x) return [];
    return x.b.features.map((feature) => {
      const mean = x.m.drift.factors.find((f) => f.feature === feature)?.meanContribution ?? 0;
      return { feature, label: featureLabel(feature), mean, shifted: Math.abs(mean) > DRIFT_BAND };
    });
  });
  /** The engine's thresholds as boundaries between 0.1-wide score bins (0.3 sits between the 3rd and 4th). */
  protected readonly thresholdMarkers = computed(() => {
    const b = this.baseline();
    if (b.state !== 'ready') return [];
    return [
      { at: Math.round(b.value.thresholds.review * 10), label: 'Review' },
      { at: Math.round(b.value.thresholds.decline * 10), label: 'Decline' },
    ];
  });

  /** Factors that aren't model features: the policy overlay's rules. */
  protected readonly policyRules = computed(() => {
    const x = this.both();
    return x ? x.m.drift.factors.filter((f) => !x.b.features.includes(f.feature)) : [];
  });

  protected readonly outcomes = computed(() => {
    const m = this.monitoring();
    if (m.state !== 'ready') return [];
    const b = this.baseline();
    return m.value.performance.outcomeBins
      .map((bin, i) => ({
        label: bandLabel(bin),
        finished: bin.finished,
        defaulted: bin.defaulted,
        rate: bin.finished ? bin.defaulted / bin.finished : null,
        training: b.state === 'ready' ? (b.value.scoreBins[i]?.defaultRate ?? null) : null,
        few: bin.finished < FEW,
      }))
      .filter((r) => r.finished > 0);
  });

  protected rate(o: { finished: number; defaulted: number }): number | null {
    return o.finished ? o.defaulted / o.finished : null;
  }

  protected selectDays(days: number): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { days: days === DEFAULT_DAYS ? null : days },
      queryParamsHandling: 'merge',
    });
  }
}
