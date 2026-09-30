import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { DEFAULT_CONFIG, LANDING_CONFIG, LandingConfig } from './config';

function render(config: LandingConfig = DEFAULT_CONFIG) {
  TestBed.configureTestingModule({ providers: [{ provide: LANDING_CONFIG, useValue: config }] });
  const f = TestBed.createComponent(App);
  f.detectChanges();
  return f.nativeElement as HTMLElement;
}

describe('App', () => {
  it('has one h1 with the headline', () => {
    const h1s = render().querySelectorAll('h1');
    expect(h1s.length).toBe(1);
    expect(h1s[0].textContent).toContain('Pay in 4, decided in milliseconds.');
  });

  it('has every section an anchor points at', () => {
    const el = render();
    const targets = Array.from(el.querySelectorAll('a[href^="#"]')).map((a) => a.getAttribute('href')!.slice(1));
    expect(targets).toEqual(expect.arrayContaining(['how-it-works', 'demo', 'architecture', 'under-the-hood']));
    for (const id of targets) expect(el.querySelector('#' + id), id).not.toBeNull();
  });

  it('explains the four steps and the four under-the-hood topics', () => {
    const el = render();
    const steps = Array.from(el.querySelectorAll('#how-it-works h3')).map((h) => h.textContent!.trim());
    expect(steps).toEqual(['Checkout', 'Score', 'Decide', 'Collect']);
    const topics = Array.from(el.querySelectorAll('#under-the-hood h3')).map((h) => h.textContent!.trim());
    expect(topics).toEqual(['The model', 'Fails safe', 'Events', 'Payments']);
  });

  it('always says it is a portfolio project, and links GitHub only when configured', () => {
    const without = render();
    expect(without.querySelector('footer')!.textContent).toContain('portfolio project, not a lender');
    expect(without.querySelector('footer a')).toBeNull();

    TestBed.resetTestingModule();
    const withLink = render({ ...DEFAULT_CONFIG, githubUrl: 'https://github.com/x/y' });
    expect(withLink.querySelector('footer a')!.getAttribute('href')).toBe('https://github.com/x/y');
  });
});
