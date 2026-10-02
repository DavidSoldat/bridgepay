import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { Applications } from '../applications';
import { CreditLimits, noCredit, overLimit } from '../credit-limits';
import { Product } from '../../catalog/products';
import { orderTotals } from '../../catalog/pricing';
import { DeliveryAddress } from '../delivery-form/delivery-form';
import { ApplicationResponse } from '../../shared/models/application';
import { CreditLimit } from '../../shared/models/credit-limit';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-checkout-confirm',
  imports: [DecimalPipe, BridgepayMark, RouterLink],
  templateUrl: './checkout-confirm.html',
  styleUrl: './checkout-confirm.css',
})
export class CheckoutConfirm {
  private readonly applications = inject(Applications);
  private readonly creditLimits = inject(CreditLimits);

  product = input.required<Product>();
  address = input.required<DeliveryAddress>();
  decided = output<ApplicationResponse>();
  editAddress = output<void>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly totals = computed(() => orderTotals(this.product().price));
  protected readonly installmentLabels = ['Today', 'Week 1', 'Week 2', 'Week 3'];

  /** undefined while loading, null when the request failed - neither blocks; the server is the real check. */
  protected readonly limit = toSignal<CreditLimit | null | undefined>(
    this.creditLimits.mine().pipe(catchError(() => of(null))),
    { initialValue: undefined },
  );
  protected readonly blocked = computed(() => noCredit(this.limit()) || overLimit(this.limit(), this.totals().total));
  protected readonly overBy = computed(() => {
    const available = this.limit()?.available;
    return available == null ? 0 : Math.round((this.totals().total - available) * 100) / 100;
  });

  confirm(): void {
    if (this.submitting() || this.blocked()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applications.checkout(this.totals().total).subscribe({
      next: (response) => this.decided.emit(response),
      error: (err: unknown) => {
        this.submitting.set(false);
        const overLimitMessage =
          err instanceof HttpErrorResponse && err.status === 422 && err.error?.error === 'OVER_LIMIT'
            ? (err.error.message as string)
            : null;
        this.error.set(overLimitMessage ?? 'Something went wrong submitting your order. Try again.');
      },
    });
  }
}
