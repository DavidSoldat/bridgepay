import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CheckoutConfirm } from './checkout-confirm';
import { Applications } from '../applications';
import { Product, findProduct } from '../../catalog/products';
import { DeliveryAddress } from '../delivery-form/delivery-form';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { CreditLimits } from '../credit-limits';
import { CreditLimit } from '../../shared/models/credit-limit';

describe('CheckoutConfirm', () => {
  // 198 -> free shipping, 16.34 tax, 214.34 total, 53.59 per installment
  const product: Product = findProduct('basin-rain-jacket')!;
  const address: DeliveryAddress = { fullName: 'Sam Shopper', street: '12 Pine Rd', city: 'Boulder', postalCode: '80302' };

  const roomy: CreditLimit = { limit: 1500, outstanding: 0, available: 1500, band: 'LOW' };

  function setup(checkout: (amount: number) => any, mine: () => any = () => of(roomy)) {
    TestBed.configureTestingModule({
      imports: [CheckoutConfirm],
      providers: [
        provideRouter([]),
        { provide: Applications, useValue: { checkout } },
        { provide: CreditLimits, useValue: { mine } },
      ],
    });
    const fixture = TestBed.createComponent(CheckoutConfirm);
    fixture.componentRef.setInput('product', product);
    fixture.componentRef.setInput('address', address);
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: ReturnType<typeof setup>) => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const confirmButton = (fixture: ReturnType<typeof setup>) =>
    (fixture.nativeElement as HTMLElement).querySelector('[data-testid="confirm"]') as HTMLButtonElement;

  it('shows the available amount next to the total', () => {
    const t = text(setup(() => of({}), () => of({ limit: 600, outstanding: 150, available: 450, band: 'LOW' })));
    expect(t).toContain('$450.00 available');
  });

  it('allows an order exactly at the available amount', () => {
    const fixture = setup(() => of({}), () => of({ limit: 600, outstanding: 385.66, available: 214.34, band: 'LOW' }));
    expect(confirmButton(fixture).disabled).toBe(false);
  });

  it('blocks an order over the available amount and says by how much', () => {
    const fixture = setup(() => of({}), () => of({ limit: 600, outstanding: 400, available: 200, band: 'LOW' }));
    expect(confirmButton(fixture).disabled).toBe(true);
    expect(text(fixture)).toContain('$14.34 over your available amount');
    expect((fixture.nativeElement as HTMLElement).querySelector('a[href="/"]')).not.toBeNull();
  });

  it('does not block when the limit could not be checked', () => {
    const fixture = setup(() => of({}), () => of({ limit: null, outstanding: 0, available: null, band: null }));
    expect(confirmButton(fixture).disabled).toBe(false);
  });

  it("shows the server's over-limit message as an alert", () => {
    const fixture = setup(() => throwError(() => new HttpErrorResponse({
      status: 422,
      error: { error: 'OVER_LIMIT', message: 'This order is $214.34; you have $100.00 available.' },
    })));
    confirmButton(fixture).click();
    fixture.detectChanges();
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('This order is $214.34; you have $100.00 available.');
  });

  it('shows the order summary with shipping and tax', () => {
    const t = text(setup(() => of({})));
    expect(t).toContain('198.00');
    expect(t).toContain('Free');
    expect(t).toContain('16.34');
    expect(t).toContain('214.34');
  });

  it('shows where the order ships', () => {
    const t = text(setup(() => of({})));
    expect(t).toContain('Sam Shopper');
    expect(t).toContain('12 Pine Rd');
    expect(t).toContain('Boulder 80302');
  });

  it('labels each installment on the timeline with when it is due and how much', () => {
    const fixture = setup(() => of({}));
    const labels = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="installment"]')).map(
      (el) => Array.from(el.querySelectorAll('span')).map((s) => s.textContent?.trim()).filter(Boolean).join(' '),
    );
    expect(labels).toEqual(['Today $53.59', 'Week 1 $53.59', 'Week 2 $53.59', 'Week 3 $53.59']);
  });

  it('finances the order total, not the bare product price', () => {
    let sent: number | undefined;
    const fixture = setup((amount) => {
      sent = amount;
      return of({});
    });

    confirmButton(fixture).click();

    expect(sent).toBe(214.34);
  });

  it('emits decided with the response on a successful checkout', () => {
    const response = { applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 53.59 };
    const fixture = setup(() => of(response));
    let emitted: unknown;
    fixture.componentInstance.decided.subscribe((r) => (emitted = r));

    confirmButton(fixture).click();

    expect(emitted).toEqual(response);
  });

  it('shows an error and does not emit when checkout fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    let emitted = false;
    fixture.componentInstance.decided.subscribe(() => (emitted = true));

    confirmButton(fixture).click();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect(text(fixture)).toContain('Something went wrong');
  });

  it('asks to edit the address when Edit is clicked', () => {
    const fixture = setup(() => of({}));
    let asked = false;
    fixture.componentInstance.editAddress.subscribe(() => (asked = true));

    ((fixture.nativeElement as HTMLElement).querySelector('[data-testid="edit-address"]') as HTMLButtonElement).click();

    expect(asked).toBe(true);
  });
});
