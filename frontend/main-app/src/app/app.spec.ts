import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(roles: string[]) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        {
          provide: Auth,
          useValue: {
            hasRole: (r: string) => roles.includes(r),
            username: () => 'test-user',
            logout: () => {},
          },
        },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('shows the Review Queue link for an ops role', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Review Queue');
    expect(text).not.toContain('Payouts');
  });

  it('shows the Payouts link for a merchant role', () => {
    const fixture = setup(['merchant']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Payouts');
    expect(text).not.toContain('Review Queue');
  });
});
