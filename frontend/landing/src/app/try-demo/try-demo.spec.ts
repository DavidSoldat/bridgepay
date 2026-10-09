import { TestBed } from '@angular/core/testing';
import { TryDemo } from './try-demo';
import { LANDING_CONFIG, LandingConfig } from '../config';

const config = (over: Partial<LandingConfig> = {}): LandingConfig => ({
  storefrontUrl: 'http://shop.example',
  appUrl: 'http://app.example',
  githubUrl: null,
  demoLogins: [
    { role: 'merchant', username: 'merchant1', password: 'merchant1' },
    { role: 'shopper', username: 'shopper1', password: 'shopper1' },
    { role: 'ops', username: 'ops1', password: 'ops1' },
  ],
  ...over,
});

function render(c: LandingConfig) {
  TestBed.configureTestingModule({ providers: [{ provide: LANDING_CONFIG, useValue: c }] });
  const f = TestBed.createComponent(TryDemo);
  f.detectChanges();
  return f.nativeElement as HTMLElement;
}

const steps = (el: HTMLElement) =>
  Array.from(el.querySelectorAll('[data-testid="walkthrough"] li')).map((li) => li.textContent!.replace(/\s+/g, ' '));

describe('TryDemo', () => {
  it('warns that the demo data is erased nightly', () => {
    const el = render(config());
    expect(el.querySelector('[data-testid="demo-notice"]')?.textContent)
      .toContain("This is a demo — don't enter real personal details. All data is erased nightly.");
  });

  it('shows shopper, ops and merchant cards in that order, each linking to its app', () => {
    const el = render(config());
    const cards = Array.from(el.querySelectorAll('app-role-card'));
    expect(cards.map((c) => c.querySelector('h3')!.textContent!.trim())).toEqual(['Shopper', 'Ops reviewer', 'Merchant']);
    expect(cards.map((c) => c.querySelector('a')!.getAttribute('href'))).toEqual([
      'http://shop.example', 'http://app.example', 'http://app.example',
    ]);
  });

  it('walks through the loop naming the demo logins', () => {
    const s = steps(render(config()));
    expect(s).toHaveLength(5);
    expect(s[0]).toContain('shopper1');
    expect(s[2]).toContain('ops1');
    expect(s[3]).toContain('4242 4242 4242 4242');
    expect(s[4]).toContain('merchant1');
  });

  it('names roles instead of logins, and shows no passwords, when the config has none', () => {
    const el = render(config({ demoLogins: [] }));
    expect(el.textContent).not.toContain('shopper1');
    expect(el.textContent).not.toContain('ops1');
    expect(el.querySelector('[data-testid="password"]')).toBeNull();
    const s = steps(el);
    expect(s[0]).toContain('as the shopper');
    expect(s[2]).toContain('as an ops reviewer');
    expect(s[4]).toContain('as the merchant');
  });

  it('uses the first login for a role', () => {
    const s = steps(render(config({ demoLogins: [
      { role: 'shopper', username: 'first.shopper', password: 'a' },
      { role: 'shopper', username: 'second.shopper', password: 'b' },
    ] })));
    expect(s[0]).toContain('first.shopper');
    expect(s[0]).not.toContain('second.shopper');
  });

  it('lets a long login name in the walkthrough wrap instead of widening the page', () => {
    const el = render(config());
    expect(el.querySelector('[data-testid="walkthrough"] strong')!.className).toContain('break-all');
  });

  it('is the #demo section', () => {
    expect(render(config()).querySelector('section#demo')).not.toBeNull();
  });
});
