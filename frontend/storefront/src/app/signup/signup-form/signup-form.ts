import { Component, inject, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Applicants } from '../applicants';

/** Separators people type in phone numbers; stripped before validating and sending. */
const PHONE_SEPARATORS = /[\s().-]/g;
/** Same rule as applicant-service's SignupRequest, applied to the stripped number. */
const PHONE = /^\+?[0-9]{7,15}$/;
/** Needs a dot in the domain: Paddle rejects `name@example`, so the plan could never be created. */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const normalizePhone = (value: string) => value.replace(PHONE_SEPARATORS, '');

function phoneNumber(control: AbstractControl<string>): ValidationErrors | null {
  return !control.value || PHONE.test(normalizePhone(control.value)) ? null : { phone: true };
}

/** Matches the server's @NotBlank: a name of only spaces is missing. */
function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return !control.value || control.value.trim() ? null : { required: true };
}

/** Credit needs legal capacity to contract; applicant-service enforces the same rule. */
const MINIMUM_AGE = 18;

function oldEnough(control: AbstractControl<string>): ValidationErrors | null {
  return !control.value || control.value <= latestBirthDate() ? null : { tooYoung: true };
}

/** The birth date of someone turning MINIMUM_AGE today, as yyyy-mm-dd in local time. */
function latestBirthDate(): string {
  const d = new Date();
  d.setFullYear(d.getFullYear() - MINIMUM_AGE);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Component({
  selector: 'app-signup-form',
  imports: [ReactiveFormsModule],
  templateUrl: './signup-form.html',
  styleUrl: './signup-form.css',
})
export class SignupForm {
  private readonly fb = inject(FormBuilder);
  private readonly applicants = inject(Applicants);

  signedUp = output<void>();
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly latestBirthDate = latestBirthDate();

  readonly form = this.fb.nonNullable.group({
    firstName: ['', [Validators.required, notBlank]],
    lastName: ['', [Validators.required, notBlank]],
    dateOfBirth: ['', [Validators.required, oldEnough]],
    email: ['', [Validators.required, Validators.pattern(EMAIL)]],
    phone: ['', [Validators.required, phoneNumber]],
  });

  protected showError(name: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[name];
    return control.invalid && control.touched;
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    const request = this.form.getRawValue();
    this.applicants.signUp({
      ...request,
      firstName: request.firstName.trim(),
      lastName: request.lastName.trim(),
      phone: normalizePhone(request.phone),
    }).subscribe({
      next: () => this.signedUp.emit(),
      error: (err: unknown) => {
        this.submitting.set(false);
        const serverMessage =
          err instanceof HttpErrorResponse && err.status === 400 ? err.error?.message : null;
        this.error.set(serverMessage || 'Could not create your profile. Check your details and try again.');
      },
    });
  }
}
