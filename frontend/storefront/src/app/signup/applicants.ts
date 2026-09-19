import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ApplicantResponse, SignupRequest } from './applicant.model';

@Injectable({ providedIn: 'root' })
export class Applicants {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/applicants`;

  getMyProfile(): Observable<ApplicantResponse> {
    return this.http.get<ApplicantResponse>(`${this.baseUrl}/me`);
  }

  signUp(request: SignupRequest): Observable<ApplicantResponse> {
    return this.http.post<ApplicantResponse>(this.baseUrl, request);
  }
}
