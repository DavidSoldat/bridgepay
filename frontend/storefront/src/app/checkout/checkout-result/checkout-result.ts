import { Component, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-checkout-result',
  imports: [DecimalPipe],
  templateUrl: './checkout-result.html',
  styleUrl: './checkout-result.css',
})
export class CheckoutResult {
  response = input.required<ApplicationResponse>();
  backToShop = output<void>();
}
