import { Component, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, of, switchMap } from 'rxjs';
import { RepaymentPlans } from '../repayment-plans';
import { RepaymentPlanResponse } from '../repayment-plan.model';

@Component({
  selector: 'app-installment-schedule',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './installment-schedule.html',
  styleUrl: './installment-schedule.css',
})
export class InstallmentSchedule {
  applicationId = input.required<string>();

  private readonly repaymentPlans = inject(RepaymentPlans);

  protected readonly loadError = signal(false);

  protected readonly plan = toSignal(
    toObservable(this.applicationId).pipe(
      switchMap((id) =>
        this.repaymentPlans.getPlan(id).pipe(
          catchError(() => {
            this.loadError.set(true);
            return of(null);
          }),
        ),
      ),
    ),
    { initialValue: null as RepaymentPlanResponse | null },
  );
}
