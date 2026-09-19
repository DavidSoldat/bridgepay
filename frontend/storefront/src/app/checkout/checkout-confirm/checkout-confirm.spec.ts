import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CheckoutConfirm } from './checkout-confirm';
import { Applications } from '../applications';
import { Product } from '../../catalog/products';

describe('CheckoutConfirm', () => {
  const product: Product = { id: 'basin-rain-jacket', name: 'Basin Rain Jacket', price: 200, swatchColor: '#3F5843' };

  function setup(checkout: (amount: number) => any) {
    TestBed.configureTestingModule({
      imports: [CheckoutConfirm],
      providers: [{ provide: Applications, useValue: { checkout } }],
    });
    const fixture = TestBed.createComponent(CheckoutConfirm);
    fixture.componentRef.setInput('product', product);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the per-installment amount', () => {
    const fixture = setup(() => of({}));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('50.00');
  });

  it('emits decided with the response on a successful checkout', () => {
    const response = { applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 };
    const fixture = setup(() => of(response));
    const component = fixture.componentInstance;
    let emitted: unknown;
    component.decided.subscribe((r) => (emitted = r));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
    button.click();

    expect(emitted).toEqual(response);
  });

  it('shows an error and does not emit when checkout fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    const component = fixture.componentInstance;
    let emitted = false;
    component.decided.subscribe(() => (emitted = true));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Something went wrong');
  });
});
