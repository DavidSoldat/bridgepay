import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { CreditLimit } from '../shared/models/credit-limit';

@Injectable({ providedIn: 'root' })
export class CreditLimits {
  private readonly http = inject(HttpClient);

  mine(): Observable<CreditLimit> {
    return this.http.get<CreditLimit>(`${environment.gatewayBaseUrl}/api/v1/applications/me/credit-limit`);
  }
}

/** Unknown, loading or failed never blocks: the server is the real check. */
export function overLimit(limit: CreditLimit | null | undefined, total: number): boolean {
  return limit?.available != null && total > limit.available;
}

/** A zero limit means not pre-qualified - different from a limit that's all used up. */
export function noCredit(limit: CreditLimit | null | undefined): boolean {
  return limit?.limit === 0;
}
