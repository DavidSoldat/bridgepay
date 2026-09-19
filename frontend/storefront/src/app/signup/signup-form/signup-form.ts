import { Component, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Applicants } from '../applicants';

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

  readonly form = this.fb.nonNullable.group({
    firstName: ['', Validators.required],
    lastName: ['', Validators.required],
    dateOfBirth: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    phone: ['', Validators.required],
  });

  submit(): void {
    if (this.form.invalid || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.applicants.signUp(this.form.getRawValue()).subscribe({
      next: () => this.signedUp.emit(),
      error: () => {
        this.submitting.set(false);
        this.error.set('Could not create your profile. Check your details and try again.');
      },
    });
  }
}
