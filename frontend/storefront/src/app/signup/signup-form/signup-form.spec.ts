import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SignupForm } from './signup-form';
import { Applicants } from '../applicants';

describe('SignupForm', () => {
  function setup(signUp: (request: unknown) => any) {
    TestBed.configureTestingModule({
      imports: [SignupForm],
      providers: [{ provide: Applicants, useValue: { signUp, getMyProfile: () => of({}) } }],
    });
    const fixture = TestBed.createComponent(SignupForm);
    fixture.detectChanges();
    return fixture;
  }

  it('emits signedUp after a successful submission', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue({
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    });
    component.submit();

    expect(emitted).toBe(true);
  });

  it('shows an error and does not emit when the request fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue({
      firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
      email: 'new@example.com', phone: '+15559876543',
    });
    component.submit();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Could not create your profile');
  });

  it('does not submit an invalid form', () => {
    let submitCalled = false;
    const fixture = setup(() => {
      submitCalled = true;
      return of({ id: 'a-1' });
    });
    fixture.componentInstance.submit();
    expect(submitCalled).toBe(false);
  });
});
