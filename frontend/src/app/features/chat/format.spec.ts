import { initials, lastSeen } from './format';

describe('format helpers', () => {
  const now = Date.UTC(2026, 9, 12, 12, 0);

  it('describes how long ago a friend was active', () => {
    expect(lastSeen(0, now)).toBe('Offline');
    expect(lastSeen(now - 10_000, now)).toBe('Last seen just now');
    expect(lastSeen(now - 5 * 60_000, now)).toBe('Last seen 5 min ago');
    expect(lastSeen(now - 3 * 3_600_000, now)).toBe('Last seen 3 h ago');
  });

  it('builds avatar initials from names', () => {
    expect(initials('Dana Reyes')).toBe('DR');
    expect(initials('bob')).toBe('B');
    expect(initials('Ana  Maria  Lopez')).toBe('AM');
  });
});
