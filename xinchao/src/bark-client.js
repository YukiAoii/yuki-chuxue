// 【连接桥与推送】Bark 推送客户端：给人的手机发通知（iOS）。不用 Bark 可以不配。
// 代码地图见 src/README.md。

export class BarkClient {
  constructor(config) { this.config = config; }

  async send(body, title = this.config.title) {
    // 【Yuki 接入 · 部署适配改动（非上游代码）2026-10-06】只记不发：
    // 返回 sent=true 让调用方照常 recordBark（防打扰计数、recentBarkMessages 都照记），
    // 但**不发任何 HTTP** —— 消息由 Yuki 的 App 侧轮询 /v1/state 取走。
    if (this.config.captureEnabled) return { sent: true, captured: true };
    if (!this.config.enabled || !this.config.key) return { sent: false, reason: 'disabled' };
    const response = await fetch(`${this.config.server}/${encodeURIComponent(this.config.key)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title,
        body,
        group: this.config.group,
        icon: this.config.icon,
        sound: this.config.sound,
        level: this.config.level
      }),
      signal: AbortSignal.timeout(15000)
    });
    if (!response.ok) throw new Error(`Bark failed: HTTP ${response.status}`);
    return { sent: true };
  }
}
