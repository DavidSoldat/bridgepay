import { Component, inject, signal } from '@angular/core';
import { Auth } from './core/auth';
import { Applicants } from './signup/applicants';
import { ProductCatalog } from './catalog/product-catalog/product-catalog';
import { SignupForm } from './signup/signup-form/signup-form';
import { CheckoutConfirm } from './checkout/checkout-confirm/checkout-confirm';
import { CheckoutResult } from './checkout/checkout-result/checkout-result';
import { PRODUCTS, Product } from './catalog/products';
import { ApplicationResponse } from './shared/models/application';

type Step = 'catalog' | 'signup' | 'confirm' | 'result';

const PENDING_PRODUCT_KEY = 'storefront.pendingProductId';

@Component({
  selector: 'app-root',
  imports: [ProductCatalog, SignupForm, CheckoutConfirm, CheckoutResult],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly auth = inject(Auth);
  private readonly applicants = inject(Applicants);

  protected readonly step = signal<Step>('catalog');
  protected readonly selectedProduct = signal<Product | null>(null);
  protected readonly result = signal<ApplicationResponse | null>(null);

  constructor() {
    const pendingId = sessionStorage.getItem(PENDING_PRODUCT_KEY);
    if (pendingId && this.auth.authenticated()) {
      const product = PRODUCTS.find((p) => p.id === pendingId);
      if (product) {
        this.selectedProduct.set(product);
        this.resumeAfterLogin();
      } else {
        sessionStorage.removeItem(PENDING_PRODUCT_KEY);
      }
    }
  }

  private resumeAfterLogin(): void {
    this.applicants.getMyProfile().subscribe({
      next: () => this.step.set('confirm'),
      error: () => this.step.set('signup'),
    });
  }

  protected onPayInFour(productId: string): void {
    const product = PRODUCTS.find((p) => p.id === productId);
    if (!product) return;
    this.selectedProduct.set(product);
    sessionStorage.setItem(PENDING_PRODUCT_KEY, productId);
    if (this.auth.authenticated()) {
      this.resumeAfterLogin();
    } else {
      this.auth.login(window.location.origin);
    }
  }

  protected onSignedUp(): void {
    this.step.set('confirm');
  }

  protected onDecided(response: ApplicationResponse): void {
    sessionStorage.removeItem(PENDING_PRODUCT_KEY);
    this.result.set(response);
    this.step.set('result');
  }

  protected backToShop(): void {
    this.step.set('catalog');
    this.selectedProduct.set(null);
    this.result.set(null);
  }
}
