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

  refund(merchantId: string, applicationId: string): Observable<MerchantSaleResponse> {
    return this.http.post<MerchantSaleResponse>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/orders/${applicationId}/refund`,
      null,
    );
  }

  exportCsv(merchantId: string, status: string): Observable<Blob> {
    return this.http.get(`${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/sales/export`, {
      params: { status },
      responseType: 'blob',
    });
  }

  dashboard(merchantId: string, days: number, tz: string): Observable<MerchantDashboard> {
    return this.http.get<MerchantDashboard>(
      `${environment.gatewayBaseUrl}/api/v1/merchants/${merchantId}/dashboard`,
      { params: { days, tz } },
    );
  }
}
