import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../../shared/models/page';
import { OpsApplicant } from '../../shared/models/ops-applicant';
import { ApplicantApplication, ApplicantPlans, CreditStanding, NotificationPage } from '../../shared/models/shopper';

/** Ops reads about one shopper, each from the service that owns the data. */
@Injectable({ providedIn: 'root' })
export class ShoppersApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayBaseUrl}/api/v1`;

  search(q: string, page = 0): Observable<Page<OpsApplicant>> {
    return this.http.get<Page<OpsApplicant>>(`${this.base}/ops/applicants`, { params: { q, page } });
  }

  applications(subject: string, page = 0): Observable<Page<ApplicantApplication>> {
    return this.http.get<Page<ApplicantApplication>>(`${this.base}/applications/applicants/${subject}`, {
      params: { page, size: 10 },
    });
  }

  creditStanding(subject: string): Observable<CreditStanding> {
    return this.http.get<CreditStanding>(`${this.base}/applications/applicants/${subject}/credit-standing`);
  }

  plans(subject: string): Observable<ApplicantPlans> {
    return this.http.get<ApplicantPlans>(`${this.base}/repayment-plans/applicants/${subject}`);
  }

  notifications(subject: string, page = 0): Observable<NotificationPage> {
    return this.http.get<NotificationPage>(`${this.base}/notifications/applicants/${subject}`, { params: { page } });
  }
}
