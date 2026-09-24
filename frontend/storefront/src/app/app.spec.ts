import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(authenticated: boolean) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: { authenticated: () => authenticated, logout: () => {} } },
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
});
