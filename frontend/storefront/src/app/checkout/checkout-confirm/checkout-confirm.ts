import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';
import { orderTotals } from '../../catalog/pricing';
import { DeliveryAddress } from '../delivery-form/delivery-form';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-checkout-confirm',
  imports: [DecimalPipe],
  templateUrl: './checkout-confirm.html',
  styleUrl: './checkout-confirm.css',
})
export class CheckoutConfirm {
  private readonly applications = inject(Applications);

  product = input.required<Product>();
  address = input.required<DeliveryAddress>();
  decided = output<ApplicationResponse>();
  editAddress = output<void>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly totals = computed(() => orderTotals(this.product().price));
  protected readonly installmentLabels = ['Today', 'Week 1', 'Week 2', 'Week 3'];

  confirm(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applications.checkout(this.totals().total).subscribe({
      next: (response) => this.decided.emit(response),
      error: () => {
        this.submitting.set(false);
        this.error.set('Something went wrong submitting your order. Try again.');
      },
    });
  }
}
