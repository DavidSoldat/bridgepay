import { Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, map, of, switchMap } from 'rxjs';
import { RepaymentPlans } from '../repayment-plans';
import { Installment } from '../repayment-plan.model';
import { ApplicationResponse } from '../../shared/models/application';
import { StatusBadge } from '../../shared/ui/status-badge/status-badge';
import { SkeletonRows } from '../../shared/ui/skeleton-rows/skeleton-rows';

type ScheduleState =
  | { kind: 'loading' }
  | { kind: 'plan'; installments: Installment[] }
  | { kind: 'projected'; installments: Installment[] }
  | { kind: 'error' };

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

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

@Component({
  selector: 'app-installment-schedule',
  imports: [DatePipe, DecimalPipe, StatusBadge, SkeletonRows],
  templateUrl: './installment-schedule.html',
  styleUrl: './installment-schedule.css',
})
export class InstallmentSchedule {
  application = input.required<ApplicationResponse>();

  private readonly repaymentPlans = inject(RepaymentPlans);

  protected readonly state = toSignal(
    toObservable(this.application).pipe(
      switchMap((app) =>
        this.repaymentPlans.getPlan(app.applicationId).pipe(
          map((plan): ScheduleState => ({ kind: 'plan', installments: plan.installments })),
          catchError((err) =>
            // 404 = approved but the plan isn't created yet (e.g. still waiting on Paddle).
            of<ScheduleState>(
              err instanceof HttpErrorResponse && err.status === 404
                ? { kind: 'projected', installments: projectInstallments(app) }
                : { kind: 'error' },
            ),
          ),
        ),
      ),
    ),
    { initialValue: { kind: 'loading' } as ScheduleState },
  );

  protected readonly installments = computed(() => {
    const s = this.state();
    return s.kind === 'plan' || s.kind === 'projected' ? s.installments : [];
  });
}
