import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ModelBaseline, ModelMonitoring } from '../shared/models/model-monitoring';

@Injectable({ providedIn: 'root' })
export class ModelApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayBaseUrl}/api/v1`;
  private readonly tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  baseline(): Observable<ModelBaseline> {
    return this.http.get<ModelBaseline>(`${this.base}/model`);
  }

  monitoring(days: number): Observable<ModelMonitoring> {
    return this.http.get<ModelMonitoring>(`${this.base}/applications/model-monitoring`, { params: { days, tz: this.tz } });
  }
}
