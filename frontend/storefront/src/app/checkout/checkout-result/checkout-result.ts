import { Component, computed, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApplicationResponse } from '../../shared/models/application';
import { Product } from '../../catalog/products';
import { weeklySchedule } from '../../catalog/pricing';
import { FirstPayment } from '../../payment/first-payment/first-payment';
import { Icon } from '../../shared/ui/icon/icon';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-checkout-result',
  imports: [DatePipe, DecimalPipe, RouterLink, FirstPayment, Icon, BridgepayMark],
  templateUrl: './checkout-result.html',
  styleUrl: './checkout-result.css',
})
export class CheckoutResult {
  response = input.required<ApplicationResponse>();
  product = input.required<Product>();

  protected readonly orderRef = computed(() => this.response().applicationId.slice(0, 8));
  /** Display only; the real plan (created asynchronously) is on /account. */
  protected readonly schedule = computed(() => weeklySchedule(new Date(), this.response().installmentCount ?? 4));
}
