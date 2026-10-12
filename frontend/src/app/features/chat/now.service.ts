import { Service, signal } from '@angular/core';

/** A coarse clock for relative times ("2 min ago"), ticking every 30 seconds. */
@Service()
export class NowService {
  readonly now = signal(Date.now());

  constructor() {
    setInterval(() => this.now.set(Date.now()), 30_000);
  }
}
