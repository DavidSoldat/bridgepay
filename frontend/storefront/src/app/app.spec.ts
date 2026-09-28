import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(authenticated: boolean, login: (redirectUri: string) => void = () => {}) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { authenticated: () => authenticated, logout: () => {}, login } },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('shows no account/sign-out links when not authenticated', () => {
    const fixture = setup(false);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('My Account');
    expect(text).not.toContain('Sign out');
  });

  it('shows My Account and Sign out links when authenticated', () => {
    const fixture = setup(true);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('My Account');
    expect(text).toContain('Sign out');
  });

  it('offers a way back to the shop from anywhere', () => {
    const fixture = setup(true);
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href="/"]');
    expect(link?.textContent?.trim()).toBe('Shop');
  });

  it('names the store and offers Sign in when signed out', () => {
    const redirects: string[] = [];
    const fixture = setup(false, (uri) => redirects.push(uri));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('header')?.textContent).toContain('Ridgeline Supply Co.');
    const signIn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Sign in') as HTMLButtonElement;
    signIn.click();
    expect(redirects).toEqual([window.location.href]);
  });
});
