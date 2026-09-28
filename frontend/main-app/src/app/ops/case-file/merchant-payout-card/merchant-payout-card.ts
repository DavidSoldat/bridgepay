import { Component, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { CaseMerchant, CasePayout } from '../../../shared/models/application-case';
import { StatusBadge } from '../../../shared/ui/status-badge/status-badge';

@Component({
  selector: 'app-merchant-payout-card',
  imports: [DatePipe, DecimalPipe, StatusBadge],
  templateUrl: './merchant-payout-card.html',
  styleUrl: './merchant-payout-card.css',
})
export class MerchantPayoutCard {
  merchant = input.required<CaseMerchant>();
  payout = input<CasePayout | null>(null);
}
