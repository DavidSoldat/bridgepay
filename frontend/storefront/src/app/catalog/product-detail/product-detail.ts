import { Component, computed, inject, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { Auth } from '../../core/auth';
import { findProduct } from '../products';
import { orderTotals } from '../pricing';
import { CreditLimits, noCredit, overLimit } from '../../checkout/credit-limits';
import { CreditLimit } from '../../shared/models/credit-limit';
import { EmptyState } from '../../shared/ui/empty-state/empty-state';
import { Icon } from '../../shared/ui/icon/icon';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-product-detail',
  imports: [DecimalPipe, RouterLink, EmptyState, Icon, BridgepayMark],
  templateUrl: './product-detail.html',
})
export class ProductDetail {
  protected readonly auth = inject(Auth);
  private readonly creditLimits = inject(CreditLimits);

  id = input.required<string>();
  protected readonly product = computed(() => findProduct(this.id()) ?? null);
  protected readonly totals = computed(() => orderTotals(this.product()?.price ?? 0));

  /** undefined while loading, null when signed out or the request failed. */
  protected readonly limit = toSignal<CreditLimit | null | undefined>(
    this.auth.authenticated() ? this.creditLimits.mine().pipe(catchError(() => of(null))) : of(null),
    { initialValue: undefined },
  );
  protected readonly noCredit = computed(() => noCredit(this.limit()));
  protected readonly blocked = computed(() => this.noCredit() || overLimit(this.limit(), this.totals().total));

  /** Logging in from here (not from /checkout) keeps Keycloak's page one Back press away from this one. */
  protected signInToCheckout(): void {
    this.auth.login(`${window.location.origin}/checkout/${this.id()}`);
  }
}
