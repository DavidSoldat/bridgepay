import { Component, inject, signal } from '@angular/core';
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

  protected readonly reviewerNote = signal('');
  protected readonly submitting = signal(false);
  protected readonly decisionError = signal<string | null>(null);

  protected maxContribution(): number {
    const factors = this.application()?.scoreFactors ?? [];
    return Math.max(1e-9, ...factors.map((f) => Math.abs(f.contribution)));
  }

  decide(decision: 'APPROVE' | 'DECLINE'): void {
    const id = this.application()?.applicationId;
    if (!id || this.submitting()) return;
    this.submitting.set(true);
    this.decisionError.set(null);
    this.applications.reviewDecision(id, decision, this.reviewerNote() || undefined).subscribe({
      next: () => this.router.navigateByUrl('/ops').catch((err) => console.error('Failed to navigate to /ops after decision', err)),
      error: () => {
        this.submitting.set(false);
        this.decisionError.set('Could not save this decision. Try again.');
      },
    });
  }
}
