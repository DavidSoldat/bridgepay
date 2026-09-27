import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
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

  const valid = {
    firstName: 'New', lastName: 'Shopper', dateOfBirth: '1995-05-05',
    email: 'new@example.com', phone: '+15559876543',
  };
  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  it('emits signedUp after a successful submission', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue(valid);
    component.submit();

    expect(emitted).toBe(true);
  });

  it('shows a generic error and does not emit when the request fails', () => {
    const fixture = setup(() => throwError(() => new Error('boom')));
    const component = fixture.componentInstance;
    let emitted = false;
    component.signedUp.subscribe(() => (emitted = true));

    component.form.setValue(valid);
    component.submit();
    fixture.detectChanges();

    expect(emitted).toBe(false);
    expect(text(fixture)).toContain('Could not create your profile');
  });

  it("shows the server's validation message when it rejects the details", () => {
    const fixture = setup(() => throwError(() => new HttpErrorResponse({
      status: 400,
      error: { error: 'VALIDATION_ERROR', message: 'phone: phone must be a valid phone number' },
    })));

    fixture.componentInstance.form.setValue(valid);
    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(text(fixture)).toContain('phone must be a valid phone number');
    expect(text(fixture)).not.toContain('Could not create your profile');
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent)
      .toContain('phone must be a valid phone number');
  });

  it('treats a name of only spaces as missing', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const firstName = fixture.componentInstance.form.controls.firstName;

    firstName.setValue('   ');
    firstName.markAsTouched();
    fixture.detectChanges();

    expect(firstName.invalid).toBe(true);
    expect(text(fixture)).toContain('Enter your first name');
  });

  it('sends names without surrounding spaces', () => {
    let sent: { firstName?: string; lastName?: string } = {};
    const fixture = setup((request) => {
      sent = request as { firstName?: string; lastName?: string };
      return of({ id: 'a-1' });
    });

    fixture.componentInstance.form.setValue({ ...valid, firstName: ' Ana ', lastName: 'Doe  ' });
    fixture.componentInstance.submit();

    expect(sent.firstName).toBe('Ana');
    expect(sent.lastName).toBe('Doe');
  });

  it('does not submit an invalid form, and shows every field problem at once', () => {
    let submitCalled = false;
    const fixture = setup(() => {
      submitCalled = true;
      return of({ id: 'a-1' });
    });

    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(submitCalled).toBe(false);
    expect(text(fixture)).toContain('Enter your first name');
    expect(text(fixture)).toContain('Enter your last name');
    expect(text(fixture)).toContain('Enter your date of birth');
    expect(text(fixture)).toContain('Enter an email like name@example.com');
    expect(text(fixture)).toContain('Enter a phone number with 7–15 digits');
  });

  it('shows no field errors before the shopper has touched anything', () => {
    const fixture = setup(() => of({ id: 'a-1' }));

    expect(text(fixture)).not.toContain('Enter your first name');
  });

  it('rejects an email without a dot in the domain', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const email = fixture.componentInstance.form.controls.email;

    email.setValue('name@example');
    email.markAsTouched();
    fixture.detectChanges();

    expect(email.invalid).toBe(true);
    expect(text(fixture)).toContain('Enter an email like name@example.com');
  });

  it('rejects a date of birth in the future', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const dateOfBirth = fixture.componentInstance.form.controls.dateOfBirth;
    const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

    dateOfBirth.setValue(tomorrow);
    dateOfBirth.markAsTouched();
    fixture.detectChanges();

    expect(dateOfBirth.invalid).toBe(true);
    expect(text(fixture)).toContain('Date of birth must be in the past');
  });

  it('accepts a phone typed with spaces, dashes and brackets, and sends digits only', () => {
    let sent: { phone?: string } = {};
    const fixture = setup((request) => {
      sent = request as { phone?: string };
      return of({ id: 'a-1' });
    });

    fixture.componentInstance.form.setValue({ ...valid, phone: '+1 (555) 123-4567' });
    fixture.componentInstance.submit();

    expect(sent.phone).toBe('+15551234567');
  });

  it('rejects a phone with too few digits even after stripping separators', () => {
    const fixture = setup(() => of({ id: 'a-1' }));
    const phone = fixture.componentInstance.form.controls.phone;

    phone.setValue('12-34');
    phone.markAsTouched();
    fixture.detectChanges();

    expect(phone.invalid).toBe(true);
    expect(text(fixture)).toContain('Enter a phone number with 7–15 digits');
  });
});
