import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Page } from '../shared/models/page';
import { AuditEntry, AuditFilters } from '../shared/models/audit';

@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);
  private readonly tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  list(filters: AuditFilters): Observable<Page<AuditEntry>> {
    const params: Record<string, string | number> = { tz: this.tz };
    for (const [key, value] of Object.entries(filters)) {
      if (value !== null && value !== undefined && value !== '') params[key] = value;
    }
    return this.http.get<Page<AuditEntry>>(`${environment.gatewayBaseUrl}/api/v1/audit`, { params });
  }
}
