import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Shop } from './shop';
import { Auth } from '../core/auth';
import { Applicants } from '../signup/applicants';

const PENDING_KEY = 'storefront.pendingProductId';

describe('Shop', () => {
  afterEach(() => sessionStorage.removeItem(PENDING_KEY));

  function setup(authenticated: boolean, getMyProfile: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [Shop],
      providers: [
        { provide: Auth, useValue: { authenticated: () => authenticated, login, logout: () => {} } },
        { provide: Applicants, useValue: { getMyProfile, signUp: () => of({}) } },
      ],
    });
    return TestBed.createComponent(Shop);
  }

  it('triggers login and stays on the catalog step when Pay in 4 is clicked while unauthenticated', () => {
    let loginCalled = false;
    const fixture = setup(false, () => of({}), () => (loginCalled = true));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(loginCalled).toBe(true);
    expect(shop.step()).toBe('catalog');
  });

  it('moves to confirm when Pay in 4 is clicked while authenticated and a profile already exists', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(shop.step()).toBe('confirm');
  });

  it('moves to signup when Pay in 4 is clicked while authenticated but no profile exists yet', () => {
    const fixture = setup(true, () => throwError(() => new Error('404')));
    const shop = fixture.componentInstance as any;

    shop.onPayInFour('basin-rain-jacket');

    expect(shop.step()).toBe('signup');
  });

  it('resumes automatically on construction when a pending checkout exists and the user is already authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));

    expect((fixture.componentInstance as any).step()).toBe('confirm');
  });

  it('moves to result and clears the pending checkout when a decision is made', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');

    shop.onDecided({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 });

    expect(shop.step()).toBe('result');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('moves to confirm when signup completes', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.onSignedUp();

    expect(shop.step()).toBe('confirm');
  });

  it('keepShopping resets to catalog and clears the pending checkout', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;

    shop.keepShopping();

    expect(shop.step()).toBe('catalog');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('backToShop behaves the same as keepShopping', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');
    shop.onDecided({ applicationId: 'app-1', status: 'DECLINED', installmentCount: null, installmentAmount: null });

    shop.backToShop();

    expect(shop.step()).toBe('catalog');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('does not resume automatically when a pending checkout exists but the user is not authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(false, () => of({}));

    expect((fixture.componentInstance as any).step()).toBe('catalog');
  });

  it('renders the product catalog on the catalog step', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-product-catalog')).toBeTruthy();
  });

  it('renders the checkout confirmation on the confirm step', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const shop = fixture.componentInstance as any;
    shop.onPayInFour('basin-rain-jacket');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-checkout-confirm')).toBeTruthy();
  });
});
