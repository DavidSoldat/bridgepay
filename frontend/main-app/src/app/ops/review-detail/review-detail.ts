import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { switchMap } from 'rxjs';
import { Applications } from '../applications';

@Component({
  selector: 'app-review-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './review-detail.html',
  styleUrl: './review-detail.css',
})
export class ReviewDetail {
  private readonly applications = inject(Applications);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly application = toSignal(
    this.route.paramMap.pipe(switchMap((params) => this.applications.getApplication(params.get('id')!))),
  );

  protected maxContribution(): number {
    const factors = this.application()?.scoreFactors ?? [];
    return Math.max(1e-9, ...factors.map((f) => Math.abs(f.contribution)));
  }

  decide(decision: 'APPROVE' | 'DECLINE'): void {
    const id = this.application()?.applicationId;
    if (!id) return;
    this.applications.reviewDecision(id, decision).subscribe(() => {
      // Swallow navigation rejection (e.g. no matching route configured, as in unit tests) —
      // the decision already succeeded, a failed redirect shouldn't surface as an app error.
      this.router.navigateByUrl('/ops').catch(() => {});
    });
  }
}
