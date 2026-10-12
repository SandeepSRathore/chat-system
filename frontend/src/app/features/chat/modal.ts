import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';

/** Native <dialog> shown modally: focus trapping, Escape and the backdrop come from the browser. */
@Component({
  selector: 'app-modal',
  template: `
    <dialog #dialog [attr.aria-labelledby]="titleId()" (close)="closed.emit()" (click)="onBackdrop($event)">
      <div class="body">
        <header>
          <h2 [id]="titleId()">{{ heading() }}</h2>
          <button type="button" class="close" (click)="dialog.close()" aria-label="Close">
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </header>
        <ng-content />
      </div>
    </dialog>
  `,
  styles: `
    dialog {
      width: min(28rem, calc(100vw - 2rem));
      max-height: min(40rem, calc(100dvh - 2rem));
      padding: 0;
      border: 1px solid var(--rule);
      border-radius: 12px;
      background: var(--surface);
      color: var(--ink);
    }
    dialog::backdrop {
      background: rgb(13 20 26 / 0.55);
    }
    dialog[open] {
      animation: rise 160ms ease-out;
    }
    .body {
      display: grid;
      gap: 1rem;
      padding: 1.25rem;
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    h2 {
      margin: 0;
      font-size: var(--step-1);
      font-weight: 600;
    }
    .close {
      display: grid;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border: 0;
      border-radius: var(--radius-sm);
      background: transparent;
      cursor: pointer;
    }
    .close:hover { background: var(--paper); }
    path { stroke: currentColor; stroke-width: 2; stroke-linecap: round; }
    @keyframes rise {
      from { opacity: 0; transform: translateY(8px); }
    }
  `,
})
export class Modal {
  readonly heading = input.required<string>();
  readonly titleId = input.required<string>();
  readonly closed = output<void>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
  }

  close(): void {
    this.dialog().nativeElement.close();
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) {
      this.close();
    }
  }
}
