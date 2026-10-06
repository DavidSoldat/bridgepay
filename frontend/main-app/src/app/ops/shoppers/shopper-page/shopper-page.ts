import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { map, shareReplay, switchMap } from 'rxjs';
import { ShoppersApi } from '../shoppers-api';
import { OpsApplicants } from '../../ops-applicants';
import { Section, toSection } from '../../../shared/models/section';
import { OpsApplicant } from '../../../shared/models/ops-applicant';
import { ApplicantPlans, CreditStanding } from '../../../shared/models/shopper';
import { StatusBadge } from '../../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../../shared/ui/skeleton-rows/skeleton-rows';
import { EmptyState } from '../../../shared/ui/empty-state/empty-state';
import { Icon } from '../../../shared/ui/icon/icon';
import { ShopperCard } from '../../case-file/shopper-card/shopper-card';
import { ShopperApplications } from '../shopper-applications/shopper-applications';
import { ShopperNotifications } from '../shopper-notifications/shopper-notifications';
import { buildTimeline, orderRef, standingReason } from '../timeline';

const TIMELINE_PREVIEW = 20;
const LOADING = { state: 'loading' } as const;

/** Everything about one shopper, each card loaded from the service that owns it and failing on its own. */
@Component({
  selector: 'app-shopper-page',
  imports: [
    RouterLink, DatePipe, DecimalPipe, StatusBadge, SkeletonRows, EmptyState, Icon,
    ShopperCard, ShopperApplications, ShopperNotifications,
  ],
  templateUrl: './shopper-page.html',
})
export class ShopperPage {
  private readonly api = inject(ShoppersApi);
  private readonly opsApplicants = inject(OpsApplicants);
  private readonly route = inject(ActivatedRoute);

  private readonly subject$ = this.route.paramMap.pipe(
    map((p) => p.get('subject')!),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  protected readonly subject = toSignal(this.subject$, { initialValue: '' });
  protected readonly profile = toSignal(this.subject$.pipe(switchMap((s) => toSection(this.opsApplicants.get(s)))), {
    initialValue: LOADING as Section<OpsApplicant>,
  });
  protected readonly standing = toSignal(this.subject$.pipe(switchMap((s) => toSection(this.api.creditStanding(s)))), {
    initialValue: LOADING as Section<CreditStanding>,
  });
  // One request feeds both the standing's "why" line and the payment timeline.
  protected readonly plans = toSignal(this.subject$.pipe(switchMap((s) => toSection(this.api.plans(s)))), {
    initialValue: LOADING as Section<ApplicantPlans>,
  });

  protected readonly heading = computed(() => {
    const p = this.profile();
    return p.state === 'ready' ? `${p.value.firstName} ${p.value.lastName}` : 'Shopper';
  });
  protected readonly reason = computed(() => {
    const p = this.plans();
    return p.state === 'ready' ? standingReason(p.value.history) : null;
  });
  /** Share of the limit in use, clamped to 0–100 (outstanding can exceed a limit that dropped, even to 0). */
  protected readonly usedPct = computed(() => {
    const s = this.standing();
    if (s.state !== 'ready' || s.value.limit === null) return 0;
    if (s.value.limit <= 0) return s.value.outstanding > 0 ? 100 : 0;
    return Math.min(100, Math.round((s.value.outstanding / s.value.limit) * 100));
  });
  protected readonly timeline = computed(() => {
    const p = this.plans();
    return p.state === 'ready' ? buildTimeline(p.value.plans) : [];
  });
  protected readonly showAll = signal(false);
  protected readonly visibleTimeline = computed(() =>
    this.showAll() ? this.timeline() : this.timeline().slice(0, TIMELINE_PREVIEW),
  );
  protected readonly orderRef = orderRef;

  protected readonly toneClass: Record<string, string> = {
    approved: 'text-approved', review: 'text-review', declined: 'text-declined', paid: 'text-paid', neutral: 'text-neutral',
  };
  protected readonly toneIcon: Record<string, string> = {
    approved: 'check', review: 'clock', declined: 'x', paid: 'check', neutral: 'arrow-left',
  };
}
