import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { switchMap } from 'rxjs';
import { Applications } from '../applications';

// The model's 10 bureau-shaped feature keys (services/credit-risk-engine's
// coefficients.json) - unknown keys fall back to the raw name as-is.
const FEATURE_LABELS: Record<string, string> = {
  revolvingUtilization: 'Revolving utilization',
  age: 'Age',
  numberOfTime30to59DaysPastDueNotWorse: '30-59 days past due',
  debtRatio: 'Debt ratio',
  monthlyIncome: 'Monthly income',
  numberOfOpenCreditLinesAndLoans: 'Open credit lines & loans',
  numberOfTimes90DaysLate: '90+ days late',
  numberRealEstateLoansOrLines: 'Real estate loans/lines',
  numberOfTime60to89DaysPastDueNotWorse: '60-89 days past due',
  numberOfDependents: 'Dependents',
};

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

  protected featureLabel(feature: string): string {
    return FEATURE_LABELS[feature] ?? feature;
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
