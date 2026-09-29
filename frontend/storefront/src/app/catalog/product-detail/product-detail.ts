import { Component, computed, inject, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Auth } from '../../core/auth';
import { findProduct } from '../products';
import { orderTotals } from '../pricing';
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

  id = input.required<string>();
  protected readonly product = computed(() => findProduct(this.id()) ?? null);
  protected readonly totals = computed(() => orderTotals(this.product()?.price ?? 0));

  /** Logging in from here (not from /checkout) keeps Keycloak's page one Back press away from this one. */
  protected signInToCheckout(): void {
    this.auth.login(`${window.location.origin}/checkout/${this.id()}`);
  }
}
