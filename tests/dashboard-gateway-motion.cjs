'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = [...html.slice(0, html.indexOf('<body')).matchAll(/<style[^>]*>[\s\S]*?<\/style>/g)].map(match => match[0]).join('');
const button = html.match(/<button id="gw-toggle"[\s\S]*?<\/button>/)[0];
const script = html.slice(html.indexOf('    let gatewayRunning'), html.indexOf('    // 通过自定义 scheme'));
assert(script.includes('function animateGatewayState'));
const fixture = `<!doctype html><html class="glass-ui"><head>${css}</head><body class="liquid-glass"><header class="glass-masthead">${button}</header><pre id="result">WAIT</pre><script>
const systemReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
let reduced = false;
window.matchMedia = () => ({matches:reduced});
let statusRevision = 0;
function clearGatewayEndpoints() {}
function renderGatewayConnectionState() {}
${script}
try {
  const toggle = document.getElementById('gw-toggle');
  const text = document.getElementById('gw-toggle-text');
  const dot = toggle.querySelector('.gw-dot');
  const ripple = toggle.querySelector('.gw-dot-ripple');
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  window.__setGatewayState(false,3435);
  check(!toggle.getAnimations({subtree:true}).length && text.innerText === '网关已关闭', 'initial state must stay still');
  const pressDuration = getComputedStyle(toggle).transitionDuration;
  const expectedDuration = systemReduced ? '0s' : '0.65s';
  check(pressDuration === expectedDuration, 'press release pacing: expected ' + expectedDuration + ', got ' + pressDuration);
  window.__setGatewayState(true,3435);
  const animations = toggle.getAnimations({subtree:true});
  check(animations.length === 3 && text.innerText === '网关运行中' && toggle.getAttribute('aria-label').includes('点击关闭'), 'running state feedback');
  const bounce = dot.getAnimations()[0];
  bounce.pause();
  const scales = [];
  for (let ms = 0; ms < 650; ms += 20) {
    bounce.currentTime = ms;
    scales.push(new DOMMatrixReadOnly(getComputedStyle(dot).transform).a);
  }
  check(Math.max(...scales) > 1.1 && Math.min(...scales) < .9 && bounce.effect.getTiming().duration === 650, 'dot squeezes and overshoots');
  const ring = ripple.getAnimations()[0]; ring.pause(); ring.currentTime = 390;
  check(Number(getComputedStyle(ripple).opacity) > 0 && ring.effect.getTiming().duration === 590, 'ripple stays visible into the rebound');
  check(scales[12] > 1.2 && scales[17] > 1.15 && scales[25] < 1, 'dot lingers above resting scale before settling');
  check(text.getAnimations()[0].effect.getTiming().duration === 470, 'status label settles gently');
  window.__setGatewayState(true,3435);
  check(dot.getAnimations()[0] === bounce, 'duplicate callback restarted the animation');
  window.__setGatewayState(false,3435);
  check(animations.every(animation => animation.playState === 'idle') && text.innerText === '网关已关闭', 'previous feedback leaked after reversal');
  reduced = true;
  window.__setGatewayState(true,3435);
  check(!toggle.getAnimations({subtree:true}).length && text.innerText === '网关运行中', 'reduced-motion feedback');
  document.getElementById('result').textContent = 'PASS';
} catch (error) { document.getElementById('result').textContent = 'FAIL ' + error.message; }
</script></body></html>`;
const result = browser.dom(fixture, 1700).match(/<pre id="result">([^<]*)<\/pre>/)?.[1];
assert.equal(result, 'PASS');
console.log('Gateway state bounce, ripple, repeated callbacks, reversal and reduced motion passed.');
