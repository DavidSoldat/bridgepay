import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Checkout } from './checkout';
import { Auth } from '../core/auth';
import { Applicants } from '../signup/applicants';
import { RepaymentPlans } from '../account/repayment-plans';
import { PaddleCheckout } from '../payment/paddle-checkout';
import { CreditLimits } from './credit-limits';

const KEY = 'storefront.checkout.basin-rain-jacket.address';
const ADDRESS = { fullName: 'Sam Shopper', street: '12 Pine Rd', city: 'Boulder', postalCode: '80302' };

describe('Checkout', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  function setup(opts: { authenticated: boolean; profile?: () => any; login?: (uri: string) => void; id?: string }) {
    TestBed.configureTestingModule({
      imports: [Checkout],
      providers: [
        provideRouter([]),
        {
          provide: Auth,
          useValue: {
            authenticated: () => opts.authenticated,
            fullName: () => 'Sam Shopper',
            login: opts.login ?? (() => {}),
            logout: () => {},
          },
        },
        { provide: Applicants, useValue: { getMyProfile: opts.profile ?? (() => of({ id: 'a-1' })), signUp: () => of({}) } },
        { provide: RepaymentPlans, useValue: { getPlan: () => of() } },
        { provide: PaddleCheckout, useValue: { enabled: false } },
        { provide: CreditLimits, useValue: { mine: () => of({ limit: null, outstanding: 0, available: null, band: null }) } },
      ],
    });
    const fixture = TestBed.createComponent(Checkout);
    fixture.componentRef.setInput('id', opts.id ?? 'basin-rain-jacket');
    fixture.detectChanges();
    return fixture;
  }
  const c = (f: any) => f.componentInstance as any;
  const el = (f: any) => f.nativeElement as HTMLElement;

  it('sends a signed-out shopper to login and back to this checkout', () => {
    let redirect = '';
    const f = setup({ authenticated: false, login: (uri) => (redirect = uri) });
    expect(redirect).toBe(`${window.location.origin}/checkout/basin-rain-jacket`);
    expect(el(f).querySelector('[data-testid="skeleton"]')).not.toBeNull();
  });

  it('shows Product not found for an unknown id without asking the shopper to log in', () => {
    let loginCalled = false;
    const f = setup({ authenticated: false, id: 'nope', login: () => (loginCalled = true) });
    expect(loginCalled).toBe(false);
    expect(el(f).textContent).toContain('Product not found');
  });

  it('goes to delivery with Account done when a profile exists', () => {
    const f = setup({ authenticated: true });
    expect(c(f).step()).toBe('delivery');
    f.detectChanges();
    expect(el(f).querySelector('[aria-current="step"]')?.textContent?.trim()).toBe('Delivery');
    expect(el(f).querySelector('app-delivery-form')).not.toBeNull();
  });

  it('goes to signup when no profile exists yet, then to delivery once signed up', () => {
    const f = setup({ authenticated: true, profile: () => throwError(() => new Error('404')) });
    expect(c(f).step()).toBe('signup');
    f.detectChanges();
    expect(el(f).querySelector('app-signup-form')).not.toBeNull();
    c(f).onSignedUp();
    expect(c(f).step()).toBe('delivery');
  });

  it('stores the address and moves to review, and back to delivery from the stepper', () => {
    const f = setup({ authenticated: true });
    c(f).onDeliverySubmitted(ADDRESS);
    expect(c(f).step()).toBe('confirm');
    expect(JSON.parse(sessionStorage.getItem(KEY)!)).toEqual(ADDRESS);
    f.detectChanges();
    expect(el(f).querySelector('app-checkout-confirm')).not.toBeNull();

    c(f).onGoTo('delivery');
    expect(c(f).step()).toBe('delivery');
    expect(c(f).address()).toEqual(ADDRESS);
  });

  it('resumes at review when an address was stored before the login redirect', () => {
    sessionStorage.setItem(KEY, JSON.stringify(ADDRESS));
    const f = setup({ authenticated: true });
    expect(c(f).step()).toBe('confirm');
    expect(c(f).address()).toEqual(ADDRESS);
  });

  it('ignores a stored value that is not an address', () => {
    sessionStorage.setItem(KEY, '"just a string"');
    expect(c(setup({ authenticated: true })).step()).toBe('delivery');
    TestBed.resetTestingModule();
    sessionStorage.setItem(KEY, '{not json');
    expect(c(setup({ authenticated: true })).step()).toBe('delivery');
  });

  it('still checks out when sessionStorage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    const f = setup({ authenticated: true });
    expect(c(f).step()).toBe('delivery');
    c(f).onDeliverySubmitted(ADDRESS);
    expect(c(f).step()).toBe('confirm');
    c(f).onDecided({ applicationId: 'app-1', status: 'DECLINED', installmentCount: null, installmentAmount: null });
    expect(c(f).step()).toBe('result');
  });

  it('clears the stored address once a decision arrives and shows the result', () => {
    const f = setup({ authenticated: true });
    c(f).onDeliverySubmitted(ADDRESS);
    c(f).onDecided({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 53.59 });
    expect(c(f).step()).toBe('result');
    expect(sessionStorage.getItem(KEY)).toBeNull();
    f.detectChanges();
    expect(el(f).querySelector('app-checkout-result')).not.toBeNull();
    expect(el(f).textContent).toContain("You're approved");
    expect(el(f).querySelectorAll('ol[aria-label="Checkout steps"] button').length).toBe(0);
  });

  it('shows an order card with the product and total on every step before the decision', () => {
    const f = setup({ authenticated: true });
    f.detectChanges();
    const card = el(f).querySelector('[data-testid="order-card"]')!;
    expect(card.textContent).toContain('Basin Rain Jacket');
    expect(card.textContent).toContain('214.34');
    expect(card.querySelector('img')?.getAttribute('src')).toBe('/products/basin-rain-jacket.webp');
  });

  it('forgets the address when the shopper keeps shopping', () => {
    const f = setup({ authenticated: true });
    c(f).onDeliverySubmitted(ADDRESS);
    c(f).clearAddress();
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });
});
