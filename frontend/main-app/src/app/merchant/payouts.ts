import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { MerchantPayoutResponse } from '../shared/models/merchant-payout';

@Injectable({ providedIn: 'root' })
export class Payouts {
  private readonly http = inject(HttpClient);

  listPayouts(merchantId: string, page = 0, size = 20): Observable<Page<MerchantPayoutResponse>> {
    return this.http.get<Page<MerchantPayoutResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/payouts`,
      { params: { page, size } },
    );
  }
}
