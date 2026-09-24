import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { RepaymentPlanResponse } from './repayment-plan.model';

@Injectable({ providedIn: 'root' })
export class RepaymentPlans {
  private readonly http = inject(HttpClient);

  getPlan(applicationId: string): Observable<RepaymentPlanResponse> {
    return this.http.get<RepaymentPlanResponse>(
      `${environment.gatewayBaseUrl}/api/v1/repayment-plans/${applicationId}`,
    );
  }
}
