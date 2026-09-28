import { TestBed } from '@angular/core/testing';
import { ShopperCard } from './shopper-card';
import { OpsApplicant } from '../../../shared/models/ops-applicant';
import { Section } from '../../../shared/models/section';

function render(section: Section<OpsApplicant>): HTMLElement {
  const fixture = TestBed.createComponent(ShopperCard);
  fixture.componentRef.setInput('section', section);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

function isoDateYearsAgo(years: number): string {
  const d = new Date();
  d.setFullYear(d.getFullYear() - years);
  d.setDate(d.getDate() - 1); // birthday was yesterday, so the age is exactly `years`
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

describe('ShopperCard', () => {
  it('shows who the shopper is, with a mail link and their age', () => {
    const el = render({
      state: 'ready',
      value: { subject: 's-1', firstName: 'Ana', lastName: 'Doe', email: 'ana@example.com', phone: '+38765123456', dateOfBirth: isoDateYearsAgo(32) },
    });
    expect(el.textContent).toContain('Ana Doe');
    expect(el.querySelector('a[href="mailto:ana@example.com"]')).not.toBeNull();
    expect(el.textContent).toContain('+38765123456');
    expect(el.textContent).toContain('· 32');
  });

  it('shows a skeleton while loading', () => {
    expect(render({ state: 'loading' }).querySelector('[data-testid="skeleton"]')).not.toBeNull();
  });

  it('shows an error in the card when the lookup fails', () => {
    expect(render({ state: 'error' }).textContent).toContain("Couldn't load shopper details.");
  });
});
