// End-to-end walkthrough against the running Docker stack that saves the README screenshots.
//
//   cd e2e && npm install
//   node screenshots.mjs ../docs/screenshots
//
// Needs a fresh database (it registers the users maya, leo and sam) and a local Chrome or Chromium:
// set CHROME_PATH if it is not at the default macOS location. Set RUN_SUFFIX to reuse a database.
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8000';
const OUT = process.argv[2] ?? '../docs/screenshots';
const RUN = process.env.RUN_SUFFIX ?? '';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
});
const desktop = { viewport: { width: 1200, height: 720 }, deviceScaleFactor: 2 };
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const shot = async (page, name) => {
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/${name}.png` });
  log('saved', name);
};

async function newUser(username, displayName, { capture } = {}) {
  const ctx = await browser.newContext(desktop);
  const page = await ctx.newPage();
  page.setDefaultTimeout(120_000);
  await page.goto(`${BASE}/register`);
  await page.getByLabel('Username').fill(username + RUN);
  await page.getByLabel('Display name').fill(displayName);
  await page.getByLabel('Password').fill('correct-horse');
  if (capture) await shot(page, capture);
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.waitForURL('**/chat');
  await page.waitForFunction(() => /chat-\d/.test(document.querySelector('app-connection-badge')?.textContent ?? ''));
  const server = (await page.locator('app-connection-badge').first().innerText()).trim();
  log(displayName, 'connected to', server);
  return { ctx, page, server, username: username + RUN, name: displayName };
}

/** The sidebar row for a conversation, matched on its name (previews can mention other people). */
function channel(page, name) {
  return page.locator('.channel').filter({ has: page.locator('.channel-name', { hasText: new RegExp(`^${name}$`) }) });
}

async function say(page, text) {
  await page.locator('#composer').fill(text);
  await page.keyboard.press('Enter');
  await page.locator('.msg.mine', { hasText: text }).locator('.state', { hasText: 'Sent' }).waitFor();
}

async function waitForBubble(page, text) {
  await page.locator('.msg .text', { hasText: text }).waitFor();
}

async function sendRequest(from, to) {
  await from.page.getByRole('button', { name: 'Add friend' }).click();
  await from.page.getByLabel('Username or name').fill(to.username);
  await from.page.getByRole('button', { name: 'Send request' }).click();
  await from.page.getByText('Request sent').waitFor();
}

// 1. Sign up: three people, spread across the two chat servers by service discovery
const maya = await newUser('maya', 'Maya Chen', { capture: '01-sign-up' });
await shot(maya.page, '02-empty-state');
const leo = await newUser('leo', 'Leo Brandt');
const sam = await newUser('sam', 'Sam Okoro');

// 2. Friend requests need consent
await sendRequest(maya, leo);
await shot(maya.page, '03-friend-request-sent');
await maya.page.keyboard.press('Escape');
await sendRequest(maya, sam);
await maya.page.keyboard.press('Escape');

await leo.page.getByRole('heading', { name: 'Friend requests' }).waitFor();
await shot(leo.page, '04-friend-request-received');
await leo.page.getByRole('button', { name: 'Accept Maya Chen' }).click();
await leo.page.locator('.head h2', { hasText: 'Maya Chen' }).waitFor();
await sam.page.getByRole('button', { name: 'Accept Maya Chen' }).click();
await sam.page.locator('.head h2', { hasText: 'Maya Chen' }).waitFor();

// 3. 1:1 chat between users on different chat servers
await channel(maya.page, 'Leo Brandt').click();
await maya.page.locator('.subtitle', { hasText: 'Online' }).waitFor();
await say(maya.page, 'Hey Leo! Discovery put me on chat-1. Where did you land?');
await waitForBubble(leo.page, 'Discovery put me on chat-1');
await say(leo.page, 'chat-2, the least-loaded server at the time.');
await waitForBubble(maya.page, 'the least-loaded server');
await say(maya.page, 'So this message went chat-1 → Kafka → router → Redis pub/sub → chat-2.');
await waitForBubble(leo.page, 'router → Redis pub/sub');
await say(leo.page, 'And it is already in Cassandra, so it survives if either of us goes offline.');
await waitForBubble(maya.page, 'already in Cassandra');
await shot(maya.page, '05-direct-chat-maya-on-chat-1');
await shot(leo.page, '06-direct-chat-leo-on-chat-2');

// 4. Group chat
await maya.page.getByRole('button', { name: 'New group' }).click();
await maya.page.getByLabel('Group name').fill('Design review');
await maya.page.getByRole('checkbox', { name: /Leo Brandt/ }).check();
await maya.page.getByRole('checkbox', { name: /Sam Okoro/ }).check();
await shot(maya.page, '07-create-group');
await maya.page.getByRole('button', { name: 'Create group' }).click();
await maya.page.locator('.head h2', { hasText: 'Design review' }).waitFor();
await say(maya.page, 'Welcome to the design review group 👋');
await channel(sam.page, 'Design review').click();
await waitForBubble(sam.page, 'Welcome to the design review group');
await say(sam.page, 'Thanks! Is presence pushed for groups too?');
await waitForBubble(maya.page, 'Is presence pushed for groups too?');
await say(maya.page, 'No, group member presence is fetched on demand when the group opens, like the book suggests.');
await channel(leo.page, 'Design review').locator('.unread').waitFor();
await shot(leo.page, '09-unread-badge');
await channel(leo.page, 'Design review').click();
await say(leo.page, 'Makes sense. Fan-out to every member would not scale for big groups.');
await waitForBubble(maya.page, 'Fan-out to every member');
await shot(maya.page, '08-group-chat');

// 5. Presence: Leo closes the browser without signing out; Maya sees him go offline after the heartbeat TTL
await channel(maya.page, 'Leo Brandt').click();
await maya.page.locator('.subtitle', { hasText: 'Online' }).waitFor();
const closedAt = Date.now();
await leo.ctx.close();
await maya.page.locator('.subtitle', { hasText: 'Last seen' }).waitFor({ timeout: 90_000 });
log(`Leo shown offline after ${Math.round((Date.now() - closedAt) / 1000)}s`);
await shot(maya.page, '10-presence-last-seen');

// 6. Failover: stop the chat server Maya is on; clients reconnect through discovery
await channel(maya.page, 'Design review').click();
const failed = maya.server;
const survivor = failed === 'chat-1' ? 'chat-2' : 'chat-1';
log('stopping', failed);
execFileSync('docker', ['stop', failed]);
try {
  await maya.page.locator('app-connection-badge .reconnecting').first().waitFor({ timeout: 5_000 });
  await maya.page.screenshot({ path: `${OUT}/11-failover-reconnecting.png` });
  log('saved 11-failover-reconnecting');
} catch {
  log('reconnect was too fast to capture the reconnecting badge');
}
await maya.page.waitForFunction((s) => document.querySelector('app-connection-badge')?.textContent?.includes(s), survivor, { timeout: 60_000 });
await sam.page.waitForFunction((s) => document.querySelector('app-connection-badge')?.textContent?.includes(s), survivor, { timeout: 60_000 });
await say(maya.page, `${failed} just went down; we both failed over to ${survivor}.`);
await waitForBubble(sam.page, 'just went down');
await shot(maya.page, '12-failover-recovered');
execFileSync('docker', ['start', failed]);
const healthUrl = `http://localhost:${failed === 'chat-1' ? 8091 : 8092}/actuator/health`;
for (let i = 0; i < 120; i++) {
  const ok = await fetch(healthUrl).then((r) => r.ok, () => false);
  if (ok) break;
  await new Promise((r) => setTimeout(r, 2_000));
}
log('restarted', failed);

// 7. Mobile layout (Sam on a phone-sized screen, Maya still online)
await sam.ctx.close();
const phone = await browser.newContext({ viewport: { width: 390, height: 780 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true });
const samPhone = await phone.newPage();
samPhone.setDefaultTimeout(120_000);
await samPhone.goto(`${BASE}/login`);
await samPhone.getByLabel('Username').fill(sam.username);
await samPhone.getByLabel('Password').fill('correct-horse');
await samPhone.getByRole('button', { name: 'Sign in' }).click();
await channel(samPhone, 'Design review').waitFor();
await shot(samPhone, '13-mobile-conversations');
await channel(samPhone, 'Design review').click();
await waitForBubble(samPhone, 'just went down');
await samPhone.waitForFunction(() => /chat-\d/.test(document.querySelector('app-connection-badge')?.textContent ?? ''));
await samPhone.locator('.subtitle', { hasText: /[1-9] online/ }).waitFor();
await shot(samPhone, '14-mobile-conversation');
await phone.close();

// 8. Dark mode follows the operating system
await maya.ctx.close();
const dark = await browser.newContext({ ...desktop, colorScheme: 'dark' });
const mayaDark = await dark.newPage();
mayaDark.setDefaultTimeout(120_000);
await mayaDark.goto(`${BASE}/login`);
await mayaDark.getByLabel('Username').fill(maya.username);
await mayaDark.getByLabel('Password').fill('correct-horse');
await mayaDark.getByRole('button', { name: 'Sign in' }).click();
await channel(mayaDark, 'Design review').click();
await waitForBubble(mayaDark, 'Fan-out to every member');
await mayaDark.waitForFunction(() => /chat-\d/.test(document.querySelector('app-connection-badge')?.textContent ?? ''));
await shot(mayaDark, '15-dark-mode');

await browser.close();
log('done');
