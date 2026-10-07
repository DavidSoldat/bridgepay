import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, filter, of, shareReplay, switchMap } from 'rxjs';
import { Applications } from '../applications';
import { OpsApplicants } from '../ops-applicants';
import { RepaymentPlans } from '../repayment-plans';
import { ApplicationCase } from '../../shared/models/application-case';
import { toSection } from '../../shared/models/section';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../../shared/ui/icon/icon';
import { ToastService } from '../../shared/ui/toast-service';
import { DecisionCard } from '../case-file/decision-card/decision-card';
import { ShopperCard } from '../case-file/shopper-card/shopper-card';
import { MerchantPayoutCard } from '../case-file/merchant-payout-card/merchant-payout-card';
import { RepaymentCard } from '../case-file/repayment-card/repayment-card';
import { AuditTrail } from '../audit-trail/audit-trail';
import { featureLabel } from '../../shared/models/feature-labels';

// The model's 10 bureau-shaped feature keys (services/credit-risk-engine's
// coefficients.json) plus PolicyOverlay's rule keys - unknown keys fall back
// to the raw name as-is.

const isCase = (c: ApplicationCase | undefined): c is ApplicationCase => c !== undefined;

@Component({
  selector: 'app-review-detail',
  imports: [
    RouterLink, DatePipe, DecimalPipe, StatusBadge, SkeletonRows, Icon,
    DecisionCard, ShopperCard, MerchantPayoutCard, RepaymentCard, AuditTrail,
  ],
  templateUrl: './review-detail.html',
  styleUrl: './review-detail.css',
})
export class ReviewDetail {
  private readonly applications = inject(Applications);
  private readonly opsApplicants = inject(OpsApplicants);
  private readonly repaymentPlans = inject(RepaymentPlans);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);

  protected readonly loadError = signal(false);

  // One request per application id, shared by the page and its per-section loaders.
  private readonly caseFile$ = this.route.paramMap.pipe(
    switchMap((params) => {
      this.loadError.set(false);
      return this.applications.getCase(params.get('id')!).pipe(
        catchError(() => {
          this.loadError.set(true);
          return of(undefined);
        }),
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  protected readonly application = toSignal(this.caseFile$);
  protected readonly shopper = toSignal(
    this.caseFile$.pipe(filter(isCase), switchMap((c) => toSection(this.opsApplicants.get(c.applicantId)))),
    { initialValue: { state: 'loading' } as const },
  );
  protected readonly plan = toSignal(
    this.caseFile$.pipe(filter(isCase), switchMap((c) => toSection(this.repaymentPlans.get(c.applicationId)))),
    { initialValue: { state: 'loading' } as const },
  );

  protected readonly reviewerNote = signal('');
  protected readonly submitting = signal(false);
  protected readonly decisionError = signal<string | null>(null);

  protected maxContribution(): number {
    const factors = this.application()?.scoreFactors ?? [];
    return Math.max(1e-9, ...factors.map((f) => Math.abs(f.contribution)));
  }

  protected featureLabel(feature: string): string {
    return featureLabel(feature);
  }

  decide(decision: 'APPROVE' | 'DECLINE'): void {
    const id = this.application()?.applicationId;
    if (!id || this.submitting()) return;
    this.submitting.set(true);
    this.decisionError.set(null);
    this.applications.reviewDecision(id, decision, this.reviewerNote() || undefined).subscribe({
      next: () => {
        this.toasts.show('success', decision === 'APPROVE' ? 'Application approved' : 'Application declined');
        this.router.navigateByUrl('/ops').catch((err) => console.error('Failed to navigate to /ops after decision', err));
      },
      error: () => {
        this.submitting.set(false);
        const message = 'Could not save this decision. Try again.';
        this.decisionError.set(message);
        this.toasts.show('error', message);
      },
    });
  }
}
