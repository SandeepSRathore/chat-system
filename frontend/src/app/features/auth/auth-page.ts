import { Component, computed, inject, input, signal } from '@angular/core';
import { FormField, form, maxLength, minLength, pattern, required, submit } from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

interface Credentials {
  username: string;
  displayName: string;
  password: string;
}

@Component({
  selector: 'app-auth-page',
  imports: [FormField, RouterLink],
  templateUrl: './auth-page.html',
  styleUrl: './auth-page.scss',
})
export class AuthPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Bound from route data. */
  readonly mode = input<'login' | 'register'>('login');
  protected readonly isRegister = computed(() => this.mode() === 'register');

  protected readonly model = signal<Credentials>({ username: '', displayName: '', password: '' });
  protected readonly serverError = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly credentials = form(this.model, (path) => {
    required(path.username, { message: 'Enter a username' });
    pattern(path.username, /^[A-Za-z0-9_]{3,32}$/, {
      message: 'Use 3–32 letters, digits or underscores',
    });
    required(path.password, { message: 'Enter a password' });
    minLength(path.password, 6, { message: 'Use at least 6 characters' });
    maxLength(path.password, 72, { message: 'Use at most 72 characters' });
    required(path.displayName, {
      message: 'Enter the name friends will see',
      when: () => this.isRegister(),
    });
    maxLength(path.displayName, 64, { message: 'Use at most 64 characters' });
  });

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.serverError.set(null);
    void submit(this.credentials, {
      action: async () => {
        const { username, displayName, password } = this.model();
        this.busy.set(true);
        try {
          if (this.isRegister()) {
            await this.auth.register(username, displayName.trim(), password);
          } else {
            await this.auth.login(username, password);
          }
          await this.router.navigate(['/chat']);
        } catch (error) {
          this.serverError.set(
            errorMessage(error, this.isRegister() ? 'Account could not be created.' : 'Sign-in failed.'),
          );
        } finally {
          this.busy.set(false);
        }
      },
    });
  }
}
