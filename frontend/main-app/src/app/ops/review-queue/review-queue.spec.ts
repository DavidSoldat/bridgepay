import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
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
        { provide: Applications, useValue: { list: () => of(page) } },
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
        { provide: Applications, useValue: { list: () => of(emptyPage) } },
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
        { provide: Applications, useValue: { list: () => throwError(() => new Error('403')) } },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Could not load the review queue');
    expect(text).not.toContain('No applications waiting for review');
  });

  it('defaults to the pending (manual review) filter', () => {
    const calls: (string | undefined)[] = [];
    const emptyPage: Page<ApplicationResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        {
          provide: Applications,
          useValue: { list: (status?: string) => (calls.push(status), of(emptyPage)) },
        },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    expect(calls).toEqual(['MANUAL_REVIEW']);
  });

  it('re-fetches with the newly selected status when a filter button is clicked', () => {
    const calls: (string | undefined)[] = [];
    const emptyPage: Page<ApplicationResponse> = {
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
    };

    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        {
          provide: Applications,
          useValue: { list: (status?: string) => (calls.push(status), of(emptyPage)) },
        },
      ],
    });

    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    const allButton = Array.from(buttons).find((b) => b.textContent?.trim() === 'All') as HTMLButtonElement;
    allButton.click();
    fixture.detectChanges();

    expect(calls).toEqual(['MANUAL_REVIEW', 'ALL']);
  });

  it('shows a skeleton while loading and the empty state only once an empty page arrives', () => {
    const response = new Subject<Page<ApplicationResponse>>();
    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [provideRouter([]), { provide: Applications, useValue: { list: () => response } }],
    });
    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('[data-testid="skeleton"]')).not.toBeNull();
    expect(el.textContent).not.toContain('No applications waiting for review');

    response.next({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    response.complete();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="skeleton"]')).toBeNull();
    expect(el.textContent).toContain('No applications waiting for review');
  });

  it('keeps showing the load error when the already-active filter is clicked again', () => {
    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { list: () => throwError(() => new Error('503')) } },
      ],
    });
    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Pending') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.textContent).toContain('Could not load the review queue');
    expect(el.textContent).not.toContain('No applications waiting for review');
  });

  it('shows each status as a badge', () => {
    const page: Page<ApplicationResponse> = {
      content: [{
        applicationId: 'app-1', applicantId: 'a-1', merchantId: 'm-1', amount: 200,
        status: 'MANUAL_REVIEW', riskScore: 0.5, scoreFactors: [],
        installmentCount: null, installmentAmount: null, decisionAt: null,
      }],
      totalElements: 1, totalPages: 1, number: 0, size: 20,
    };
    TestBed.configureTestingModule({
      imports: [ReviewQueue],
      providers: [provideRouter([]), { provide: Applications, useValue: { list: () => of(page) } }],
    });
    const fixture = TestBed.createComponent(ReviewQueue);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('tbody [data-tone="review"]')?.textContent?.trim()).toBe('In review');
  });
});
