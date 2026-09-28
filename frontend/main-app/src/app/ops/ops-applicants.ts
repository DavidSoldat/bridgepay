import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { OpsApplicant } from '../shared/models/ops-applicant';

@Injectable({ providedIn: 'root' })
export class OpsApplicants {
  private readonly http = inject(HttpClient);

  get(subject: string): Observable<OpsApplicant> {
    return this.http.get<OpsApplicant>(`${environment.gatewayBaseUrl}/api/v1/ops/applicants/${subject}`);
  }
}
