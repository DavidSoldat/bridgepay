import { Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, concatMap, first, of, take, timer } from 'rxjs';
import { RepaymentPlans } from '../../account/repayment-plans';
import { PaddleCheckout } from '../paddle-checkout';

export const POLL_INTERVAL_MS = 1000;
export const POLL_ATTEMPTS = 20;

type State = 'loading' | 'none' | 'timeout' | 'due' | 'paying' | 'closed' | 'failed' | 'paid';

/**
 * Installment 1 is paid by hand through Paddle's overlay; that checkout saves the card on the Paddle
 * subscription, which charges the remaining installments automatically.
 */
@Component({
  selector: 'app-first-payment',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './first-payment.html',
})
export class FirstPayment implements OnInit {
  applicationId = input.required<string>();
  /** Checkout result screen: the plan is created asynchronously after approval, so poll, then auto-open. */
  awaitPlan = input(false);

  private readonly plans = inject(RepaymentPlans);
  private readonly paddle = inject(PaddleCheckout);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<State>('loading');
  protected readonly amount = signal(0);
  private transactionId: string | null = null;

  ngOnInit(): void {
    if (!this.paddle.enabled) {
      console.warn('paddleClientToken missing from /config.json; first-payment step hidden');
      this.state.set('none');
      return;
    }
    timer(0, POLL_INTERVAL_MS)
      .pipe(
        take(this.awaitPlan() ? POLL_ATTEMPTS : 1),
        concatMap(() => this.plans.getPlan(this.applicationId()).pipe(catchError(() => of(null)))),
        first((plan) => plan !== null, null),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((plan) => {
        if (plan === null) {
          this.state.set(this.awaitPlan() ? 'timeout' : 'none');
        } else if (!plan.checkoutTransactionId) {
          this.state.set('none');
        } else {
          this.transactionId = plan.checkoutTransactionId;
          this.amount.set(plan.installmentAmount);
          this.state.set('due');
          if (this.awaitPlan()) {
            void this.pay();
          }
        }
      });
  }

  protected async pay(): Promise<void> {
    if (!this.transactionId) return;
    this.state.set('paying');
    try {
      const outcome = await this.paddle.open(this.transactionId);
      this.state.set(outcome === 'completed' ? 'paid' : 'closed');
    } catch {
      this.state.set('failed');
    }
  }
}
