import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { EarlyPaymentScope, RepaymentPlanResponse } from './repayment-plan.model';

@Injectable({ providedIn: 'root' })
export class RepaymentPlans {
  private readonly http = inject(HttpClient);

  getPlan(applicationId: string): Observable<RepaymentPlanResponse> {
    return this.http.get<RepaymentPlanResponse>(
      `${environment.gatewayBaseUrl}/api/v1/repayment-plans/${applicationId}`,
    );
  }

  /** 200 = applied (body is the updated plan); 202 = Paddle took the payment but it isn't applied yet. */
  payEarly(applicationId: string, scope: EarlyPaymentScope): Observable<HttpResponse<RepaymentPlanResponse>> {
    return this.http.post<RepaymentPlanResponse>(
      `${environment.gatewayBaseUrl}/api/v1/repayment-plans/${applicationId}/early-payment`,
      { scope },
      { observe: 'response' },
    );
  }
}
