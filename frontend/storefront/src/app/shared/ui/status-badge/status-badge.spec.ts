import { TestBed } from '@angular/core/testing';
import { StatusBadge } from './status-badge';

function render(status: string): HTMLElement {
  const fixture = TestBed.createComponent(StatusBadge);
  fixture.componentRef.setInput('status', status);
  fixture.detectChanges();
  return (fixture.nativeElement as HTMLElement).querySelector('span')!;
}

describe('StatusBadge', () => {
  it.each([
    ['APPROVED', 'Approved', 'approved'],
    ['ACTIVE', 'Active', 'approved'],
    ['RESOLVED', 'Resolved', 'approved'],
    ['PENDING', 'Pending', 'review'],
    ['MANUAL_REVIEW', 'In review', 'review'],
    ['RETRYING', 'Retrying', 'review'],
    ['LATE', 'Late', 'review'],
    ['REFUND_PENDING', 'Refund pending', 'review'],
    ['DECLINED', 'Declined', 'declined'],
    ['DEFAULTED', 'Defaulted', 'declined'],
    ['FAILED', 'Failed', 'declined'],
    ['MISSED', 'Missed', 'declined'],
    ['PAID', 'Paid', 'paid'],
    ['COMPLETED', 'Completed', 'paid'],
    ['CANCELLED', 'Cancelled', 'neutral'],
    ['REFUNDED', 'Refunded', 'neutral'],
    ['SCHEDULED', 'Scheduled', 'neutral'],
  ])('shows %s as "%s" in %s colours', (status, label, tone) => {
    const badge = render(status);
    expect(badge.textContent?.trim()).toBe(label);
    expect(badge.getAttribute('data-tone')).toBe(tone);
  });

  it('falls back to the raw value in neutral colours for a status it does not know', () => {
    const badge = render('ON_HOLD');
    expect(badge.textContent?.trim()).toBe('ON_HOLD');
    expect(badge.getAttribute('data-tone')).toBe('neutral');
  });
});
