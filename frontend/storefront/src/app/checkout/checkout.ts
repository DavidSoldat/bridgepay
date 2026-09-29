import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../core/auth';
import { Applicants } from '../signup/applicants';
import { findProduct } from '../catalog/products';
import { orderTotals } from '../catalog/pricing';
import { SignupForm } from '../signup/signup-form/signup-form';
import { DeliveryForm, DeliveryAddress } from './delivery-form/delivery-form';
import { CheckoutConfirm } from './checkout-confirm/checkout-confirm';
import { CheckoutResult } from './checkout-result/checkout-result';
import { CheckoutSteps, CheckoutStepKey } from './checkout-steps/checkout-steps';
import { ApplicationResponse } from '../shared/models/application';
import { EmptyState } from '../shared/ui/empty-state/empty-state';
import { SkeletonRows } from '../shared/ui/skeleton-rows/skeleton-rows';
import { Icon } from '../shared/ui/icon/icon';

type Step = 'loading' | 'signup' | 'delivery' | 'confirm' | 'result';

const STEP_KEYS: Record<Step, CheckoutStepKey> = {
  loading: 'account',
  signup: 'account',
  delivery: 'delivery',
  confirm: 'confirm',
  result: 'result',
};

// ponytail: the component is reused if only :id changes (/checkout/a -> /checkout/b), which no link in the
// app does; re-run ngOnInit on id changes if one ever does.
@Component({
  selector: 'app-checkout',
  imports: [DecimalPipe, RouterLink, SignupForm, DeliveryForm, CheckoutConfirm, CheckoutResult, CheckoutSteps,
    EmptyState, SkeletonRows, Icon],
  templateUrl: './checkout.html',
})
export class Checkout implements OnInit {
  private readonly auth = inject(Auth);
  private readonly applicants = inject(Applicants);
  private readonly router = inject(Router);

  id = input.required<string>();

  protected readonly product = computed(() => findProduct(this.id()) ?? null);
  protected readonly totals = computed(() => orderTotals(this.product()?.price ?? 0));
  protected readonly step = signal<Step>('loading');
  protected readonly stepKey = computed(() => STEP_KEYS[this.step()]);
  protected readonly address = signal<DeliveryAddress | null>(null);
  protected readonly result = signal<ApplicationResponse | null>(null);

  ngOnInit(): void {
    if (!this.product()) return;
    if (!this.auth.authenticated()) {
      this.auth.login(`${window.location.origin}/checkout/${this.id()}`);
      return;
    }
    this.address.set(this.loadAddress());
    this.applicants.getMyProfile().subscribe({
      next: () => this.step.set(this.address() ? 'confirm' : 'delivery'),
      error: () => this.step.set('signup'),
    });
  }

  protected onSignedUp(): void {
    this.step.set('delivery');
  }

  protected onDeliverySubmitted(address: DeliveryAddress): void {
    this.address.set(address);
    try {
      sessionStorage.setItem(this.addressKey(), JSON.stringify(address));
    } catch {
      // storage blocked: checkout still works, it just won't survive a reload
    }
    this.step.set('confirm');
  }

  protected onGoTo(key: CheckoutStepKey): void {
    if (key === 'delivery' && this.step() === 'confirm') this.step.set('delivery');
  }

  protected onDecided(response: ApplicationResponse): void {
    this.clearAddress();
    this.result.set(response);
    this.step.set('result');
  }

  protected clearAddress(): void {
    try {
      sessionStorage.removeItem(this.addressKey());
    } catch {
      // nothing stored if storage is blocked
    }
  }

  /** Replaced in Task 6, when the result screen links back itself. */
  protected backToShop(): void {
    this.router.navigateByUrl('/');
  }

  private addressKey(): string {
    return `storefront.checkout.${this.id()}.address`;
  }

  private loadAddress(): DeliveryAddress | null {
    try {
      const parsed = JSON.parse(sessionStorage.getItem(this.addressKey()) ?? 'null');
      return parsed && typeof parsed === 'object' && typeof parsed.street === 'string' ? parsed : null;
    } catch {
      return null;
    }
  }
}
