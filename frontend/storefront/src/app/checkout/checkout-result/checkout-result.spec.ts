import { TestBed } from '@angular/core/testing';
import { CheckoutResult } from './checkout-result';

describe('CheckoutResult', () => {
  function setup(status: string) {
    TestBed.configureTestingModule({ imports: [CheckoutResult] });
    const fixture = TestBed.createComponent(CheckoutResult);
    fixture.componentRef.setInput('response', {
      applicationId: 'app-1', status, installmentCount: 4, installmentAmount: 50,
    });
    fixture.detectChanges();
    return fixture;
  }

  it('shows an approval message for APPROVED', () => {
    const fixture = setup('APPROVED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("You're approved");
  });

  it('shows a review message for MANUAL_REVIEW', () => {
    const fixture = setup('MANUAL_REVIEW');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('under review');
  });

  it('shows a decline message for DECLINED', () => {
    const fixture = setup('DECLINED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("couldn't be approved");
  });
});
