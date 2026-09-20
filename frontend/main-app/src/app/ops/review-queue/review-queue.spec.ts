import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ReviewQueue } from './review-queue';
import { Applications } from '../applications';
import { Page } from '../../shared/models/page';
import { ApplicationResponse } from '../../shared/models/application';

describe('ReviewQueue', () => {
  it('renders a row per application returned by the service', () => {
    const page: Page<ApplicationResponse> = {
      content: [
        {
          applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
          status: 'MANUAL_REVIEW', riskScore: 0.5, scoreFactors: [],
          installmentCount: null, installmentAmount: null, decisionAt: '2026-09-12T00:00:00Z',
        },
      ],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { listManualReview: () => of(page) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('m-1');
    expect(text).toContain('200.00');
  });

  it('shows an empty-state message when there are no applications', () => {
    const emptyPage: Page<ApplicationResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { listManualReview: () => of(emptyPage) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'No applications waiting for review',
    );
  });

  it('shows a distinct error message instead of the empty state when the request fails', () => {
    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { listManualReview: () => throwError(() => new Error('403')) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Could not load the review queue');
    expect(text).not.toContain('No applications waiting for review');
  });
});
