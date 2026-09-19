import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { ApplicationResponse } from '../shared/models/application';

@Injectable({ providedIn: 'root' })
export class Applications {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/applications`;

  listManualReview(page = 0, size = 20): Observable<Page<ApplicationResponse>> {
    return this.http.get<Page<ApplicationResponse>>(this.baseUrl, {
      params: { status: 'MANUAL_REVIEW', page, size },
    });
  }

  getApplication(id: string): Observable<ApplicationResponse> {
    return this.http.get<ApplicationResponse>(`${this.baseUrl}/${id}`);
  }

  reviewDecision(
    id: string,
    decision: 'APPROVE' | 'DECLINE',
    reviewerNote?: string,
  ): Observable<ApplicationResponse> {
    return this.http.post<ApplicationResponse>(`${this.baseUrl}/${id}/review-decision`, {
      decision,
      reviewerNote,
    });
  }
}
