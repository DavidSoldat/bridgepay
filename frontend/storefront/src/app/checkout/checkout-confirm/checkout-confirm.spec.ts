import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CheckoutConfirm } from './checkout-confirm';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';
import { DeliveryAddress } from '../delivery-form/delivery-form';

describe('CheckoutConfirm', () => {
  // 198 -> free shipping, 16.34 tax, 214.34 total, 53.59 per installment
  const product: Product = { id: 'basin-rain-jacket', name: 'Basin Rain Jacket', price: 198, swatchColor: '#3F5843' };
  const address: DeliveryAddress = { fullName: 'Sam Shopper', street: '12 Pine Rd', city: 'Boulder', postalCode: '80302' };

  function setup(checkout: (amount: number) => any) {
    TestBed.configureTestingModule({
      imports: [CheckoutConfirm],
      providers: [{ provide: Applications, useValue: { checkout } }],
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
