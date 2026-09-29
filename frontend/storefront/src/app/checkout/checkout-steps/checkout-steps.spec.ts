import { TestBed } from '@angular/core/testing';
import { CheckoutSteps, CheckoutStepKey } from './checkout-steps';

describe('CheckoutSteps', () => {
  function render(current: CheckoutStepKey) {
    const fixture = TestBed.createComponent(CheckoutSteps);
    fixture.componentRef.setInput('current', current);
    fixture.detectChanges();
    return fixture;
  }
  const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;
  const states = (f: { nativeElement: unknown }) =>
    Array.from(el(f).querySelectorAll('li[data-state]')).map((li) => li.getAttribute('data-state'));

  it('lists the four steps in order and marks the current one', () => {
    const f = render('delivery');
    const labels = Array.from(el(f).querySelectorAll('li[data-state]')).map((li) => li.textContent?.trim());
    expect(labels).toEqual(['Account', 'Delivery', 'Review & pay', 'Decision']);
    expect(el(f).querySelector('[aria-current="step"]')?.textContent?.trim()).toBe('Delivery');
    expect(states(f)).toEqual(['done', 'current', 'upcoming', 'upcoming']);
  });

  it('summarises the position for phone widths', () => {
    expect(el(render('confirm')).querySelector('[data-testid="step-summary"]')?.textContent?.trim())
      .toBe('Step 3 of 4 · Review & pay');
  });

  it('lets the shopper go back to Delivery from Review & pay', () => {
    const f = render('confirm');
    const emitted: CheckoutStepKey[] = [];
    f.componentInstance.goTo.subscribe((k) => emitted.push(k));
    const buttons = el(f).querySelectorAll('ol button');
    expect(buttons.length).toBe(1);
    (buttons[0] as HTMLButtonElement).click();
    expect(emitted).toEqual(['delivery']);
  });

  it('offers no way back once the decision is shown, and Account is never a button', () => {
    expect(el(render('result')).querySelectorAll('ol button').length).toBe(0);
    expect(el(render('delivery')).querySelectorAll('ol button').length).toBe(0);
    expect(states(render('result'))).toEqual(['done', 'done', 'done', 'current']);
  });
});
