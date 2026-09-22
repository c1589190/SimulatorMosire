const { chromium } = require('/home/cna/.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');

(async () => {
  const out = process.argv[2];
  const browser = await chromium.launch({
    executablePath: '/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--no-proxy-server', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://127.0.0.1:5817/', { waitUntil: 'networkidle', timeout: 60000 });
  await page.waitForTimeout(3500);
  await page.screenshot({ path: out });
  console.log('TITLE=' + (await page.title()));
  console.log('URL=' + page.url());
  await browser.close();
})().catch((e) => { console.error('ERR=' + e.message); process.exit(1); });
