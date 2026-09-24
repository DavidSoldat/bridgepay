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
    expect(text).not.toContain('Sales');
  });

  it('shows the Sales and Payouts links for a merchant role', () => {
    const fixture = setup(['merchant']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const links = Array.from(el.querySelectorAll('aside a')).map((a) => [a.textContent?.trim(), a.getAttribute('href')]);
    expect(links).toEqual([
      ['Sales', '/merchant'],
      ['Payouts', '/merchant/payouts'],
    ]);
    expect(el.textContent).not.toContain('Review Queue');
  });
});
