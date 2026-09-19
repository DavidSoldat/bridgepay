import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { App } from './app';
import { Auth } from './core/auth';
import { Applicants } from './signup/applicants';

const PENDING_KEY = 'storefront.pendingProductId';

describe('App', () => {
  afterEach(() => sessionStorage.removeItem(PENDING_KEY));

  function setup(authenticated: boolean, getMyProfile: () => any, login = () => {}) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: Auth, useValue: { authenticated: () => authenticated, login, logout: () => {} } },
        { provide: Applicants, useValue: { getMyProfile, signUp: () => of({}) } },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('triggers login and stays on the catalog step when Pay in 4 is clicked while unauthenticated', () => {
    let loginCalled = false;
    const fixture = setup(false, () => of({}), () => (loginCalled = true));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(loginCalled).toBe(true);
    expect(app.step()).toBe('catalog');
  });

  it('moves to confirm when Pay in 4 is clicked while authenticated and a profile already exists', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(app.step()).toBe('confirm');
  });

  it('moves to signup when Pay in 4 is clicked while authenticated but no profile exists yet', () => {
    const fixture = setup(true, () => throwError(() => new Error('404')));
    const app = fixture.componentInstance as any;

    app.onPayInFour('basin-rain-jacket');

    expect(app.step()).toBe('signup');
  });

  it('resumes automatically on construction when a pending checkout exists and the user is already authenticated', () => {
    sessionStorage.setItem(PENDING_KEY, 'basin-rain-jacket');
    const fixture = setup(true, () => of({ id: 'a-1' }));

    expect((fixture.componentInstance as any).step()).toBe('confirm');
  });

  it('moves to result and clears the pending checkout when a decision is made', () => {
    const fixture = setup(true, () => of({ id: 'a-1' }));
    const app = fixture.componentInstance as any;
    app.onPayInFour('basin-rain-jacket');

    app.onDecided({ applicationId: 'app-1', status: 'APPROVED', installmentCount: 4, installmentAmount: 50 });

    expect(app.step()).toBe('result');
    expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
  });
});
