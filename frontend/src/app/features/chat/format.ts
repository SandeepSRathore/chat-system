import { Pipe, PipeTransform } from '@angular/core';

const clock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const day = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });

/** "14:05" for today, "Mar 3" for older. */
@Pipe({ name: 'shortTime' })
export class ShortTimePipe implements PipeTransform {
  transform(epochMs: number | null | undefined, now: number): string {
    if (!epochMs) {
      return '';
    }
    return new Date(epochMs).toDateString() === new Date(now).toDateString()
      ? clock.format(epochMs)
      : day.format(epochMs);
  }
}

@Pipe({ name: 'clockTime' })
export class ClockTimePipe implements PipeTransform {
  transform(epochMs: number): string {
    return clock.format(epochMs);
  }
}

/** "Last seen 4 min ago" style text from the presence server's lastActiveAt. */
export function lastSeen(lastActiveAt: number, now: number): string {
  if (!lastActiveAt) {
    return 'Offline';
  }
  const minutes = Math.round((now - lastActiveAt) / 60_000);
  if (minutes < 1) {
    return 'Last seen just now';
  }
  if (minutes < 60) {
    return `Last seen ${minutes} min ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `Last seen ${hours} h ago`;
  }
  return `Last seen ${day.format(lastActiveAt)}`;
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join('');
}
