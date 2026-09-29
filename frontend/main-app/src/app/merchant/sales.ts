import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { MerchantSaleResponse } from '../shared/models/merchant-sale';
import { MerchantDashboard } from '../shared/models/merchant-dashboard';

@Injectable({ providedIn: 'root' })
export class Sales {
  private readonly http = inject(HttpClient);

  list(merchantId: string, status: string, page = 0, size = 20): Observable<Page<MerchantSaleResponse>> {
    return this.http.get<Page<MerchantSaleResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/sales`,
      { params: { status, page, size } },
    );
  }

  dashboard(merchantId: string, days: number, tz: string): Observable<MerchantDashboard> {
    return this.http.get<MerchantDashboard>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/dashboard`,
      { params: { days, tz } },
    );
  }
}
