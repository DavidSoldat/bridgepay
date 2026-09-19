import { Component, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';
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
  decided = output<ApplicationResponse>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected installmentAmount(): number {
    return this.product().price / 4;
  }

  confirm(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applications.checkout(this.product().price).subscribe({
      next: (response) => this.decided.emit(response),
      error: () => {
        this.submitting.set(false);
        this.error.set('Something went wrong submitting your order. Try again.');
      },
    });
  }
}
