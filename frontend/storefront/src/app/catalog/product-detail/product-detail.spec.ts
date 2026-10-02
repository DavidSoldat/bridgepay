import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ProductDetail } from './product-detail';
import { Auth } from '../../core/auth';
import { of, throwError } from 'rxjs';
import { CreditLimits } from '../../checkout/credit-limits';
import { CreditLimit } from '../../shared/models/credit-limit';

describe('ProductDetail', () => {
  function render(
    id: string,
    auth: { authenticated: () => boolean; login?: (uri: string) => void } = { authenticated: () => true },
    mine: () => any = () => of({ limit: 1500, outstanding: 0, available: 1500, band: 'LOW' } as CreditLimit),
  ) {
    TestBed.configureTestingModule({
      imports: [ProductDetail],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { login: () => {}, ...auth } },
        { provide: CreditLimits, useValue: { mine } },
      ],
    });
    const fixture = TestBed.createComponent(ProductDetail);
    fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const checkoutButton = (el: HTMLElement) => el.querySelector('[data-testid="checkout"]') as HTMLElement;
  const spendLine = (el: HTMLElement) =>
    el.querySelector('[data-testid="spending-power"]')?.textContent?.replace(/\s+/g, ' ').trim();

  it('tells a signed-in shopper how much they can spend', () => {
    const el = render('basin-rain-jacket', undefined, () => of({ limit: 600, outstanding: 150, available: 450, band: 'LOW' }));
    expect(spendLine(el)).toBe('You have $450.00 available to spend with BridgePay.');
    expect(checkoutButton(el).hasAttribute('disabled')).toBe(false);
  });

  it('disables Pay in 4 when the order total is over the available amount', () => {
    // basin-rain-jacket finances 214.34
    const el = render('basin-rain-jacket', undefined, () => of({ limit: 600, outstanding: 400, available: 200, band: 'LOW' }));
    expect(el.textContent).toContain('This order is over your available amount.');
    expect(checkoutButton(el).tagName).toBe('BUTTON');
    expect(checkoutButton(el).hasAttribute('disabled')).toBe(true);
  });

  it('says BridgePay is not available when the limit is zero', () => {
    const el = render('basin-rain-jacket', undefined, () => of({ limit: 0, outstanding: 0, available: 0, band: 'HIGH' }));
    expect(el.textContent).toContain("BridgePay isn't available for your account right now.");
    expect(el.textContent).not.toContain('over your available amount');
    expect(checkoutButton(el).hasAttribute('disabled')).toBe(true);
  });

  it('keeps Pay in 4 enabled when the limit could not be checked', () => {
    const el = render('basin-rain-jacket', undefined, () => of({ limit: null, outstanding: 0, available: null, band: null }));
    expect(spendLine(el)).toBeUndefined();
    expect(checkoutButton(el).hasAttribute('disabled')).toBe(false);
  });

  it('keeps Pay in 4 enabled when the limit request fails', () => {
    const el = render('basin-rain-jacket', undefined, () => throwError(() => new Error('boom')));
    expect(checkoutButton(el).hasAttribute('disabled')).toBe(false);
  });

  it('does not ask for a limit when signed out, and invites the shopper to sign in', () => {
    const mine = vi.fn(() => of({} as CreditLimit));
    const el = render('basin-rain-jacket', { authenticated: () => false }, mine);
    expect(mine).not.toHaveBeenCalled();
    expect(el.textContent).toContain('Sign in to see how much you can spend.');
  });

  it('shows the product with its photo, copy and features', () => {
    const el = render('basin-rain-jacket');
    expect(el.querySelector('h1')?.textContent).toContain('Basin Rain Jacket');
    const img = el.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/products/basin-rain-jacket.webp');
    expect(img.getAttribute('alt')!.length).toBeGreaterThan(10);
    expect(el.textContent).toContain('198.00');
    expect(el.textContent).toContain('Pit zips for venting on climbs');
  });

  it('prices Pay in 4 on the financed total, spelled out', () => {
    const el = render('basin-rain-jacket');
    expect(el.querySelector('[data-testid="installment"]')?.textContent).toContain('4 × $53.59');
    expect(el.querySelector('[data-testid="total-line"]')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('$214.34 total incl. $16.34 tax · free shipping');
    expect(el.querySelector('app-bridgepay-mark')).not.toBeNull();
  });

  it('names the shipping charge under the free-shipping threshold', () => {
    const el = render('insulated-field-bottle');
    expect(el.querySelector('[data-testid="installment"]')?.textContent).toContain('4 × $13.11');
    expect(el.querySelector('[data-testid="total-line"]')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('$52.42 total incl. $3.47 tax · $6.95 shipping');
  });

  it('links the checkout button to this product', () => {
    const el = render('camp-multitool');
    const link = el.querySelector('[data-testid="checkout"]')!;
    expect(link.getAttribute('href')).toBe('/checkout/camp-multitool');
    expect(link.textContent?.trim()).toBe('Check out with Pay in 4');
  });

  it('shows Product not found with a way back for an unknown id', () => {
    const el = render('nope');
    expect(el.textContent).toContain('Product not found');
    expect(el.querySelector('a[href="/"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="checkout"]')).toBeNull();
  });

  it('sends a signed-out shopper to login from the product page, so Back returns here rather than to Keycloak', () => {
    let redirect = '';
    const el = render('camp-multitool', { authenticated: () => false, login: (uri) => (redirect = uri) });
    const button = el.querySelector('[data-testid="checkout"]') as HTMLElement;
    expect(button.getAttribute('href')).toBeNull();
    expect(button.textContent?.trim()).toBe('Check out with Pay in 4');
    button.click();
    expect(redirect).toBe(`${window.location.origin}/checkout/camp-multitool`);
  });
});
