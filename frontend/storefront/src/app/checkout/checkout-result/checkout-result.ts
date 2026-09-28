import { Component, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ApplicationResponse } from '../../shared/models/application';
import { FirstPayment } from '../../payment/first-payment/first-payment';
import { Icon } from '../../shared/ui/icon/icon';
import { BridgepayMark } from '../../shared/ui/bridgepay-mark/bridgepay-mark';

@Component({
  selector: 'app-checkout-result',
  imports: [DecimalPipe, FirstPayment, Icon, BridgepayMark],
  templateUrl: './checkout-result.html',
  styleUrl: './checkout-result.css',
})
export class CheckoutResult {
  response = input.required<ApplicationResponse>();
  backToShop = output<void>();
}
