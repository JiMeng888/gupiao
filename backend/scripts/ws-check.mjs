/**
 * WebSocket 推送模块连通性验证脚本。
 *
 * 用 Node 22 内置的全局 WebSocket（无需第三方依赖），覆盖：
 *   握手 → ready → ping/pong → 订阅（含非法代码）→ 接收推送 → 退订 → 协议容错
 *
 * 协议以 WsProtocol.java 为准：
 *   下行 ready         {type, msg}
 *   下行 subscribed    {type, codes, snapshots, rejected:[{code,reason}]}
 *   下行 quote         {type, code, name, price, change, ...}  ← 扁平结构
 *   下行 unsubscribed  {type, codes, remaining}
 *   下行 pong          {type, ts}                               ← epoch 秒
 *
 * 用法：node ws-check.mjs [wsUrl]
 */

const URL = process.argv[2] || 'ws://localhost:18080/ws/quote';

const SUBSCRIBE_CODES = ['600519', '000001', '300750'];
const INVALID_CODES = ['NOTACODE', '123456789'];
const LISTEN_MS = 9000;

let readyMsg = null;
let pongMsg = null;
let subscribedMsgs = [];
let unsubscribedMsg = null;
let errorMsgs = [];
let quoteMsgs = [];
let closeInfo = null;
let gotPongWithUnknownField = false;

const timeout = setTimeout(() => {
  console.log('\n[结果] 超时，强制结束');
  finish();
}, LISTEN_MS + 15000);

function finish() {
  clearTimeout(timeout);

  // 合法的 subscribe 回执（codes 与请求一致的那一条）
  const okSub = subscribedMsgs.find(
    (m) => JSON.stringify(m.codes) === JSON.stringify(SUBSCRIBE_CODES));

  const byCode = {};
  for (const q of quoteMsgs) {
    byCode[q.code] = (byCode[q.code] || 0) + 1;
  }

  console.log('\n================ 验证结果 ================');
  console.log(`1. 收到 ready            : ${readyMsg ? JSON.stringify(readyMsg) : '否'}`);
  console.log(`2. 收到 pong             : ${pongMsg ? JSON.stringify(pongMsg) : '否'}`);
  console.log(`   未知字段的 ping 也响应 : ${gotPongWithUnknownField ? '是' : '否'}`);
  console.log(`3. subscribed 回执条数   : ${subscribedMsgs.length}`);
  console.log(`   合法订阅回执          : ${okSub ? `codes=${JSON.stringify(okSub.codes)} 快照数=${(okSub.snapshots || []).length} 拒绝数=${(okSub.rejected || []).length}` : '无'}`);
  const rejAll = subscribedMsgs.flatMap((m) => m.rejected || []);
  console.log(`   全部被拒项            : ${JSON.stringify(rejAll)}`);
  console.log(`4. quote 帧数            : ${quoteMsgs.length}`);
  console.log(`   各代码推送次数        : ${JSON.stringify(byCode)}`);
  console.log(`5. 退订回执              : ${unsubscribedMsg ? JSON.stringify(unsubscribedMsg) : '否'}`);
  console.log(`6. error 帧              : ${errorMsgs.length} 条 ${JSON.stringify(errorMsgs.slice(0, 3))}`);
  console.log(`7. 连接关闭              : ${closeInfo || '仍打开'}`);

  if (quoteMsgs.length > 0) {
    const s = quoteMsgs[quoteMsgs.length - 1];
    const nowSec = Math.floor(Date.now() / 1000);
    console.log(`\n   最后一帧行情          : ${JSON.stringify(s)}`);
    console.log(`   时间戳单位校验        : ts=${s.ts}，与当前秒级时间相差 ${nowSec - s.ts}s（个位数即为秒级）`);
  }

  const checks = [
    ['握手返回 ready', !!readyMsg],
    ['ping 有 pong 响应', !!pongMsg],
    ['未知字段不导致解析失败（Jackson 注解生效）', gotPongWithUnknownField],
    ['pong 时间戳为 epoch 秒', !!pongMsg && Math.abs(Math.floor(Date.now() / 1000) - pongMsg.ts) < 60],
    ['合法订阅回执与请求一致', !!okSub],
    ['订阅时立即返回快照', !!okSub && (okSub.snapshots || []).length > 0],
    ['非法代码被拒绝', rejAll.length === INVALID_CODES.length],
    ['收到行情推送', quoteMsgs.length > 0],
    ['推送覆盖全部 3 只标的', Object.keys(byCode).length === SUBSCRIBE_CODES.length],
    ['退订回执已返回，且 remaining 不含被退订项',
      !!unsubscribedMsg && !(unsubscribedMsg.remaining || []).includes('300750')],
    ['非 JSON 上行只回错误、不粗暴断连', closeInfo === null],
    ['未知 action 的 error 不回显客户端输入',
      errorMsgs.some((m) => m.message === 'unknown action') &&
      !JSON.stringify(errorMsgs).includes('definitely-not-an-action')],
  ];

  console.log('\n---------------- 断言 ----------------');
  let allPass = true;
  for (const [name, ok] of checks) {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
    if (!ok) allPass = false;
  }
  console.log(`\n总体：${allPass ? '全部通过' : '存在失败项'}`);
  process.exit(allPass ? 0 : 1);
}

const ws = new WebSocket(URL);

ws.addEventListener('open', () => console.log(`[open] 已连接 ${URL}`));

ws.addEventListener('message', (ev) => {
  const raw = typeof ev.data === 'string' ? ev.data : String(ev.data);
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    console.log(`[recv] 非 JSON 帧: ${raw.slice(0, 120)}`);
    return;
  }

  switch (msg.type) {
    case 'ready':
      readyMsg = msg;
      console.log(`[ready] ${raw}`);
      runFlow();
      break;
    case 'quote':
      quoteMsgs.push(msg);
      break;
    case 'subscribed':
      subscribedMsgs.push(msg);
      console.log(`[subscribed] ${raw.slice(0, 240)}`);
      break;
    case 'unsubscribed':
      unsubscribedMsg = msg;
      console.log(`[unsubscribed] ${raw}`);
      break;
    case 'error':
      errorMsgs.push(msg);
      console.log(`[error] ${raw}`);
      break;
    case 'pong':
      if (!pongMsg) {
        // runFlow() 发出的第一条 ping 带了一个未知字段 unknownField。
        // 若 @JsonIgnoreProperties(ignoreUnknown=true) 不生效，
        // 该帧会反序列化失败并回 error，我们就不会在这里收到 pong。
        // 因此「收到第一个 pong」本身就证明未知字段被容忍。
        pongMsg = msg;
        gotPongWithUnknownField = true;
        console.log(`[pong/带未知字段的ping] ${raw}`);
      } else {
        console.log(`[pong] ${raw}`);
      }
      break;
    default:
      console.log(`[other] ${raw.slice(0, 200)}`);
  }
});

ws.addEventListener('error', (ev) => console.log(`[error] ${ev.message || ev.type}`));
ws.addEventListener('close', (ev) => {
  closeInfo = `code=${ev.code} reason=${ev.reason || '(空)'}`;
  console.log(`[close] ${closeInfo}`);
});

function runFlow() {
  // 协议容错：带未知字段的 ping（验证 @JsonIgnoreProperties 是否生效）
  ws.send(JSON.stringify({ action: 'ping', unknownField: 'should-be-ignored' }));
  ws.send(JSON.stringify({ action: 'subscribe', codes: SUBSCRIBE_CODES }));
  ws.send(JSON.stringify({ action: 'subscribe', codes: INVALID_CODES }));
  // 未知 action 与非法 JSON：应当回 error 而不是断连
  ws.send(JSON.stringify({ action: 'definitely-not-an-action' }));
  ws.send('this is not json at all');

  setTimeout(() => {
    ws.send(JSON.stringify({ action: 'unsubscribe', codes: ['300750'] }));
  }, LISTEN_MS - 1500);

  setTimeout(() => finish(), LISTEN_MS);
}
