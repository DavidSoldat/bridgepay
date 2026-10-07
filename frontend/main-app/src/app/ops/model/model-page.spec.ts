import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { ModelPage } from './model-page';
import { ModelApi } from '../model-api';
import { ModelBaseline, ModelMonitoring } from '../../shared/models/model-monitoring';

const share = [0.2, 0.2, 0.15, 0.1, 0.1, 0.08, 0.07, 0.05, 0.03, 0.02];
const baseline: ModelBaseline = {
  modelVersion: 'abc123def456', aucRoc: 0.8213, evalRows: 22500, evalDefaultRate: 0.067,
  features: ['age', 'debtRatio'],
  scoreBins: share.map((s, i) => ({ from: i / 10, to: (i + 1) / 10, share: s, defaultRate: i / 50 })),
  thresholds: { review: 0.3, decline: 0.7 },
};

function monitoring(over: { scored?: number; finished?: number } = {}): ModelMonitoring {
  const scored = over.scored ?? 100;
  return {
    days: 30, from: '2026-09-08', to: '2026-10-07',
    drift: {
      scored,
      scoreBins: share.map((s, i) => ({ from: i / 10, to: (i + 1) / 10, count: Math.round(s * scored) })),
      factors: [
        { feature: 'priorDefault', count: 3, fireRate: 0.03, meanContribution: 3 },
        { feature: 'age', count: scored, fireRate: 1, meanContribution: 0.41 },
        { feature: 'debtRatio', count: scored, fireRate: 1, meanContribution: -0.05 },
      ],
    },
    performance: {
      finished: over.finished ?? 40,
      outcomeBins: share.map((_, i) => ({
        from: i / 10, to: (i + 1) / 10, finished: i === 0 ? 30 : i === 4 ? 10 : 0, defaulted: i === 4 ? 2 : 0,
      })),
      reviews: { decided: 10, agreedWithModel: 7, opsApproved: { finished: 5, defaulted: 1 }, modelApproved: { finished: 35, defaulted: 1 } },
    },
  };
}

async function setup(url: string, api: {
  baseline?: () => Observable<ModelBaseline>;
  monitoring?: (d: number) => Observable<ModelMonitoring>;
} = {}) {
  const calls: number[] = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'ops/model', component: ModelPage }]),
      {
        provide: ModelApi,
        useValue: {
          baseline: api.baseline ?? (() => of(baseline)),
          monitoring: (d: number) => (calls.push(d), (api.monitoring ?? (() => of(monitoring())))(d)),
        },
      },
    ],
  });
  const harness = await RouterTestingHarness.create(url);
  return { harness, calls, el: harness.routeNativeElement as HTMLElement, router: TestBed.inject(Router) };
}

const text = (el: Element | null) => el?.textContent ?? '';

describe('ModelPage', () => {
  it('shows model version, AUC and a stable PSI when production matches training', async () => {
    const { el, calls } = await setup('/ops/model');
    expect(calls).toEqual([30]);
    expect(text(el)).toContain('abc123def456');
    expect(text(el)).toContain('0.82');
    expect(text(el)).toContain('Stable');
  });

  it('reads and writes the period in ?days=', async () => {
    const { harness, el, calls, router } = await setup('/ops/model?days=7');
    expect(calls).toEqual([7]);
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === '90 days') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/ops/model?days=90');
    expect(calls.at(-1)).toBe(90);
  });

  it('flags shifted model features and lists policy rules separately', async () => {
    const { el } = await setup('/ops/model');
    const drift = el.querySelector('[data-testid="feature-drift"]');
    expect(text(drift)).toContain('Age');
    expect(text(drift)).toContain('Shifted');
    expect(text(drift)).not.toContain('Prior BridgePay default');
    const rules = el.querySelector('[data-testid="policy-rules"]');
    expect(text(rules)).toContain('Prior BridgePay default');
    expect(text(rules)).toContain('3%');
  });

  it('compares outcomes per band with training and marks thin bands', async () => {
    const { el } = await setup('/ops/model');
    const outcomes = el.querySelector('[data-testid="outcomes"]');
    expect(text(outcomes)).toContain('0.4–0.5');
    expect(text(outcomes)).toContain('20.0%'); // 2 of 10 defaulted
    expect(text(outcomes)).toContain('8.0%'); // training defaultRate 4/50
    expect(text(outcomes)).toContain('few');
    expect(text(el)).toContain('70%'); // 7 of 10 reviews agreed
  });

  it('shows not-enough-data states below the minimums', async () => {
    const { el } = await setup('/ops/model', { monitoring: () => of(monitoring({ scored: 12, finished: 3 })) });
    expect(text(el)).toContain('Not enough scored applications yet (12 of 50)');
    expect(text(el)).toContain('Not enough finished plans yet (3 of 20)');
    expect(text(el)).not.toContain('Stable');
    expect(el.querySelector('[data-testid="feature-drift"]')).toBeNull();
  });

  it('fails each source on its own', async () => {
    const { el } = await setup('/ops/model', { baseline: () => throwError(() => new Error('x')) });
    expect(text(el)).toContain("Couldn't load the training baseline.");
    expect(text(el)).toContain('70%'); // reviews need only production data
    TestBed.resetTestingModule();
    const other = await setup('/ops/model', { monitoring: () => throwError(() => new Error('x')) });
    expect(text(other.el)).toContain("Couldn't load production data for this period.");
    expect(text(other.el)).toContain('abc123def456');
  });
});
