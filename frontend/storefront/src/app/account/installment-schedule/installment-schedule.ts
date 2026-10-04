import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, map, of, switchMap } from 'rxjs';
import { RepaymentPlans } from '../repayment-plans';
import { EarlyPaymentScope, Installment, RepaymentPlanResponse } from '../repayment-plan.model';
import { ApplicationResponse } from '../../shared/models/application';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

type ScheduleState =
  | { kind: 'loading' }
  | { kind: 'plan'; plan: RepaymentPlanResponse }
  | { kind: 'projected'; installments: Installment[] }
  | { kind: 'error' };

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;
// No server message (gateway 502/504, timeout, network): the charge may still have gone through.
const PAYMENT_UNCONFIRMED = "We couldn't confirm your payment. Check this page again in a few minutes before trying again.";

// Mirrors repayment-reconciliation's plan: first installment on the approval date, then weekly.
function projectInstallments(app: ApplicationResponse): Installment[] {
  const start = new Date(app.decisionAt ?? Date.now()).getTime();
  return Array.from({ length: app.installmentCount ?? 4 }, (_, i) => ({
    sequenceNumber: i + 1,
    dueDate: new Date(start + i * WEEK_MS).toISOString(),
    amount: app.installmentAmount ?? 0,
    status: 'SCHEDULED',
    paidAt: null,
  }));
}

const cents = (amount: number) => Math.round(amount * 100);

@Component({
  selector: 'app-installment-schedule',
  imports: [DatePipe, DecimalPipe, StatusBadge, SkeletonRows],
  templateUrl: './installment-schedule.html',
  styleUrl: './installment-schedule.css',
})
export class InstallmentSchedule {
  application = input.required<ApplicationResponse>();
  /** An early payment was applied - the shopper's available limit changed. */
  readonly paid = output<void>();

  private readonly repaymentPlans = inject(RepaymentPlans);

  private readonly loaded = toSignal(
    toObservable(this.application).pipe(
      switchMap((app) =>
        this.repaymentPlans.getPlan(app.applicationId).pipe(
          map((plan): ScheduleState => ({ kind: 'plan', plan })),
          catchError((err) =>
            // 404 on an approved order = the plan isn't created yet (e.g. still waiting on Paddle).
            of<ScheduleState>(
              err instanceof HttpErrorResponse && err.status === 404 && app.status === 'APPROVED'
                ? { kind: 'projected', installments: projectInstallments(app) }
                : { kind: 'error' },
            ),
          ),
        ),
      ),
    ),
    { initialValue: { kind: 'loading' } as ScheduleState },
  );
  /** The loaded state, replaced in place by the plan an early payment returns. */
  protected readonly state = linkedSignal(() => this.loaded());

  protected readonly installments = computed(() => {
    const s = this.state();
    return s.kind === 'plan' ? s.plan.installments : s.kind === 'projected' ? s.installments : [];
  });

  private readonly activePlan = computed(() => {
    const s = this.state();
    // Before installment 1 is paid, FirstPayment owns the plan (Paddle checkout), not this component.
    return s.kind === 'plan' && s.plan.status === 'ACTIVE' && s.plan.checkoutTransactionId === null ? s.plan : null;
  });
  private readonly scheduled = computed(
    () => this.activePlan()?.installments.filter((i) => i.status === 'SCHEDULED') ?? [],
  );
  protected readonly hasMissed = computed(
    () => this.activePlan()?.installments.some((i) => i.status === 'LATE') ?? false,
  );
  protected readonly processing = signal(false);
  protected readonly canPayEarly = computed(
    () => !this.hasMissed() && this.scheduled().length > 0 && !this.processing(),
  );
  protected readonly scheduledCount = computed(() => this.scheduled().length);
  protected readonly nextAmount = computed(() => this.scheduled()[0]?.amount ?? 0);
  protected readonly remainingAmount = computed(
    () => this.scheduled().reduce((sum, i) => sum + cents(i.amount), 0) / 100,
  );

  protected readonly confirming = signal<EarlyPaymentScope | null>(null);
  protected readonly paying = signal(false);
  protected readonly payError = signal<string | null>(null);
  protected readonly payNotice = signal<string | null>(null);

  protected pay(scope: EarlyPaymentScope): void {
    this.paying.set(true);
    this.payError.set(null);
    this.payNotice.set(null);
    this.repaymentPlans.payEarly(this.application().applicationId, scope).subscribe({
      next: (res) => {
        this.paying.set(false);
        this.confirming.set(null);
        if (res.status === 202 || !res.body) {
          this.processing.set(true);
          this.payNotice.set('Payment is processing — it will show here shortly.');
          return;
        }
        this.state.set({ kind: 'plan', plan: res.body });
        this.payNotice.set(res.body.status === 'COMPLETED' ? 'Plan paid off.' : 'Payment received.');
        this.paid.emit();
      },
      error: (err: unknown) => {
        this.paying.set(false);
        this.confirming.set(null);
        const message = err instanceof HttpErrorResponse ? err.error?.message : null;
        this.payError.set(typeof message === 'string' && message ? message : PAYMENT_UNCONFIRMED);
      },
    });
  }
}
