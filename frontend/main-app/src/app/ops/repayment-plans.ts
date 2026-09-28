import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { RepaymentPlan } from '../shared/models/repayment-plan';

@Injectable({ providedIn: 'root' })
export class RepaymentPlans {
  private readonly http = inject(HttpClient);

  get(applicationId: string): Observable<RepaymentPlan> {
    return this.http.get<RepaymentPlan>(`${environment.gatewayBaseUrl}/api/v1/repayment-plans/${applicationId}`);
  }
}
