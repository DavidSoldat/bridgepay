import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, LowerCasePipe } from '@angular/common';
import { map } from 'rxjs';
import { Payouts } from '../payouts';
import { Auth } from '../../core/auth';
import { MerchantPayoutResponse } from '../../shared/models/merchant-payout';

@Component({
  selector: 'app-payout-ledger',
  imports: [DatePipe, DecimalPipe, LowerCasePipe],
  templateUrl: './payout-ledger.html',
  styleUrl: './payout-ledger.css',
})
export class PayoutLedger {
  private readonly payouts = inject(Payouts);
  private readonly auth = inject(Auth);

  protected readonly rows = toSignal(
    this.payouts.listPayouts(this.auth.merchantId() ?? '').pipe(map((page) => page.content)),
    { initialValue: [] as MerchantPayoutResponse[] },
  );
}
