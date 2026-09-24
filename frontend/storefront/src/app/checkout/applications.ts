import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicationResponse } from '../shared/models/application';
import { Page } from '../shared/models/page';

const RIDGELINE_MERCHANT_ID = '00000000-0000-7000-8000-000000000001';

@Injectable({ providedIn: 'root' })
export class Applications {
  private readonly http = inject(HttpClient);

  checkout(amount: number): Observable<ApplicationResponse> {
    return this.http.post<ApplicationResponse>(
      `${environment.gatewayBaseUrl}/api/v1/applications`,
      { merchantId: RIDGELINE_MERCHANT_ID, amount },
      { headers: { 'Idempotency-Key': crypto.randomUUID() } },
    );
  }

  listMine(page = 0, size = 20): Observable<Page<ApplicationResponse>> {
    return this.http.get<Page<ApplicationResponse>>(
      `${environment.gatewayBaseUrl}/api/v1/applications/me`,
      { params: { page, size } },
    );
  }
}
