import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicationResponse } from '../shared/models/application';

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
}
