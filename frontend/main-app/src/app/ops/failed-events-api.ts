import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { FailedEvent } from '../shared/models/failed-event';

@Injectable({ providedIn: 'root' })
export class FailedEventsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.gatewayBaseUrl}/api/v1/ops/failed-events`;

  list(status: string, page: number, size = 20): Observable<Page<FailedEvent>> {
    return this.http.get<Page<FailedEvent>>(this.baseUrl, { params: { status, page, size } });
  }

  retry(id: string): Observable<FailedEvent> {
    return this.http.post<FailedEvent>(`${this.baseUrl}/${id}/retry`, {});
  }
}
