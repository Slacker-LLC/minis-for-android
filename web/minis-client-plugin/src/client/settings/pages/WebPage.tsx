import { useCallback, useEffect, useState } from 'react'
import type { JsonObject, MinisCommand } from '../../contract/types.ts'
import { arrayOf, booleanOf, numberOf, objectOf, textOf } from '../../contract/types.ts'
import { Button, Card, Field, Form, Grid, Note, omitBlank } from '../components/Common.tsx'
import styles from '../MinisSettings.module.css'

/**
 * Minis 控制台 · Web 远程。
 *
 * Cloudflare Tunnel 状态与控制是 Android `CloudflareTunnelManager` 的远程投影
 * —— 这里没有第二个 cloudflared、没有第二份状态。所有读取来自 /api/status 的
 * `tunnel` 快照，所有操作经 controller 转发到 /api/tunnel/* 。
 */

interface TunnelView {
  phase?: string
  detail?: string
  installed?: boolean
  running?: boolean
  version?: string
  connectedProtocol?: string
  configuredProtocol?: string
  edgeConnected?: number
  edgeExpected?: number
  uptimeMs?: number
  lastConnectedAtMs?: number
  lastDisconnectedAtMs?: number
  reconnectCount?: number
  lastError?: string
  hostname?: string
  origin?: string
  originHealth?: string
  publicHealth?: string
}

function tunnelOf(data: unknown): TunnelView {
  const obj = objectOf(data)
  return obj as TunnelView
}

function phaseLabel(phase: string | undefined): string {
  switch (phase) {
    case 'unconfigured': return '未配置'
    case 'stopped': return '已停止'
    case 'starting': return '启动中'
    case 'connecting': return '连接中'
    case 'healthy': return '正常'
    case 'degraded': return '连接降级'
    case 'reconnecting': return '重连中'
    case 'auth-failed': return '认证失败'
    case 'origin-down': return '本地服务异常'
    case 'edge-down': return '连接异常'
    case 'process-exited': return '进程异常退出'
    case 'error': return '错误'
    default: return phase ?? '未知'
  }
}

function phaseTone(phase: string | undefined): 'neutral' | 'normal' | 'warning' | 'error' {
  switch (phase) {
    case 'healthy': return 'normal'
    case 'degraded': case 'reconnecting': case 'connecting': case 'starting': return 'warning'
    case 'error': case 'auth-failed': case 'origin-down': case 'edge-down': case 'process-exited': return 'error'
    default: return 'neutral'
  }
}

function fmtUptime(ms: number | undefined): string {
  if (!ms || ms <= 0) return '—'
  const totalMin = Math.floor(ms / 60_000)
  if (totalMin < 60) return `${totalMin}m`
  const h = Math.floor(totalMin / 60)
  const m = totalMin % 60
  if (h < 24) return `${h}h ${m}m`
  const d = Math.floor(h / 24)
  return `${d}d ${h % 24}h`
}

function fmtTime(ms: number | undefined): string {
  if (!ms || ms <= 0) return '—'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function WebPage({ data, busy, run }: { data: JsonObject; busy: boolean; run: (command: MinisCommand) => void }) {
  const settings = objectOf(data.settings)
  const status = objectOf(data.status)
  const tunnel = tunnelOf(status.tunnel)
  const phase = tunnel.phase
  const tone = phaseTone(phase)
  const isRunning = tunnel.running === true || phase === 'healthy' || phase === 'degraded' || phase === 'reconnecting'
  const isTransition = phase === 'starting' || phase === 'connecting' || phase === 'reconnecting'
  const diagnostics = arrayOf(data.diagnostics).map(objectOf)

  const [showDetails, setShowDetails] = useState(false)
  const [showDiagnostics, setShowDiagnostics] = useState(false)
  const [showLogs, setShowLogs] = useState(false)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [logs, setLogs] = useState<JsonObject[]>([])
  const [events, setEvents] = useState<JsonObject[]>([])

  const loadTunnelDetails = useCallback(async () => {
    try {
      const raw = await fetch('/api/tunnel/status', { credentials: 'same-origin' })
      const body = objectOf(await raw.json())
      setEvents(arrayOf(body.events).map(objectOf))
    } catch { /* 状态页已有快照，详情失败静默 */ }
  }, [])

  const loadLogs = useCallback(async () => {
    try {
      const raw = await fetch('/api/tunnel/logs?limit=60', { credentials: 'same-origin' })
      const body = objectOf(await raw.json())
      setLogs(arrayOf(body.logs).map(objectOf))
    } catch { /* 同上 */ }
  }, [])

  useEffect(() => {
    if (showDetails) void loadTunnelDetails()
  }, [showDetails, loadTunnelDetails])

  useEffect(() => {
    if (showLogs) void loadLogs()
  }, [showLogs, loadLogs])

  const confirmAndRun = (command: MinisCommand, confirmText: string) => {
    if (window.confirm(confirmText)) run(command)
  }

  const hasToken = booleanOf(settings.cloudflareTunnelTokenConfigured)

  return (
    <>
      <Note danger>端口或局域网监听变更需要重启 Web 远程服务。修改账号或密码会注销所有网页会话。</Note>
      <Grid>
        <Card>
          <h3>服务</h3>
          <Form onSubmit={payload => run({ kind: 'web-service', payload: { port: Number(payload.port), lanAccess: booleanOf(payload.lanAccess) } })}>
            <Field label="端口"><input className={styles.input} type="number" min={1024} max={65535} name="port" defaultValue={numberOf(settings.port, 8765)} /></Field>
            <label className={styles.check}><input type="checkbox" name="lanAccess" defaultChecked={booleanOf(settings.lanAccess)} /> 开放局域网监听</label>
            <Button type="submit" primary disabled={busy}>保存服务设置</Button>
          </Form>
        </Card>
        <Card>
          <h3>账号</h3>
          <Form onSubmit={payload => run({ kind: 'web-identity', payload: omitBlank(payload, ['newPassword']) })}>
            <Field label="用户名"><input className={styles.input} name="username" defaultValue={textOf(settings.username)} /></Field>
            <Field label="当前密码"><input className={styles.input} type="password" name="currentPassword" autoComplete="current-password" required /></Field>
            <Field label="新密码（可选）"><input className={styles.input} type="password" name="newPassword" autoComplete="new-password" /></Field>
            <Button type="submit" primary disabled={busy}>更新账号</Button>
          </Form>
        </Card>
        <Card wide>
          <div className={styles.cardTitleRow}>
            <h3>Cloudflare Tunnel</h3>
            <span className={`${styles.statusBadge} ${styles[`status-${tone}`]}`}>{phaseLabel(phase)}</span>
          </div>
          <p className={styles.muted}>{tunnel.hostname ? `https://${tunnel.hostname}` : '通过 Cloudflare Named Tunnel 从公网安全访问此设备。'}</p>

          {phase !== 'unconfigured' && (
            <div className={styles.statGrid}>
              <div className={styles.statItem}><span>连接状态</span><strong>{phaseLabel(phase)}</strong></div>
              <div className={styles.statItem}><span>Edge 连接</span><strong>{tunnel.edgeConnected ?? 0} / {tunnel.edgeExpected ?? 0}</strong></div>
              <div className={styles.statItem}><span>协议</span><strong>{tunnel.connectedProtocol ?? '—'}</strong></div>
              <div className={styles.statItem}><span>运行时间</span><strong>{fmtUptime(tunnel.uptimeMs)}</strong></div>
            </div>
          )}
          {tunnel.detail && phase !== 'unconfigured' && (
            <p className={styles.muted}>{tunnel.detail}</p>
          )}

          <div className={styles.actions}>
            {isRunning ? (
              <Button danger disabled={busy || isTransition} onClick={() => confirmAndRun(
                { kind: 'tunnel-stop' },
                '停止 Cloudflare Tunnel？如果你当前通过这个公网地址访问，停止后本页面将无法继续控制手机。',
              )}>停止</Button>
            ) : (
              <Button primary disabled={busy || isTransition || !hasToken} onClick={() => run({ kind: 'tunnel-start' })}>启动</Button>
            )}
            {isRunning && (
              <Button disabled={busy || isTransition} onClick={() => confirmAndRun(
                { kind: 'tunnel-restart' },
                '重启 Cloudflare Tunnel？当前远程连接会短暂中断。Tunnel 恢复后页面将尝试自动重新连接。',
              )}>重启</Button>
            )}
            <Button disabled={busy || isTransition} onClick={() => { setShowDiagnostics(v => !v); if (!showDiagnostics) run({ kind: 'tunnel-diagnose' }) }}>运行诊断</Button>
            <Button disabled={busy} onClick={() => setShowLogs(v => !v)}>查看日志</Button>
          </div>

          {phase !== 'unconfigured' && (
            <details open={showDetails} className={styles.collapse}>
              <summary onClick={() => setShowDetails(v => !v)}>连接详情</summary>
              <div className={styles.detailRows}>
                <div><span>cloudflared</span><strong>{tunnel.version || '—'}</strong></div>
                <div><span>Transport</span><strong>{tunnel.connectedProtocol ?? '—'}</strong></div>
                <div><span>Edge Connections</span><strong>{tunnel.edgeConnected ?? 0} / {tunnel.edgeExpected ?? 0}</strong></div>
                <div><span>Origin</span><strong>{tunnel.origin ?? '—'}</strong></div>
                <div><span>Origin Health</span><strong>{tunnel.originHealth ?? '—'}</strong></div>
                <div><span>Public Hostname</span><strong>{tunnel.hostname || '—'}</strong></div>
                <div><span>Public Health</span><strong>{tunnel.publicHealth ?? '—'}</strong></div>
                <div><span>启动时间</span><strong>{fmtTime(tunnel.lastConnectedAtMs)}</strong></div>
                <div><span>最近断开</span><strong>{fmtTime(tunnel.lastDisconnectedAtMs)}</strong></div>
                <div><span>重连次数</span><strong>{tunnel.reconnectCount ?? 0}</strong></div>
                <div><span>最近错误</span><strong>{tunnel.lastError || '—'}</strong></div>
              </div>
              <div className={styles.muted}>
                {events.length === 0 ? '暂无生命周期事件' : (
                  <ul className={styles.eventList}>
                    {events.slice(-12).map((e, i) => (
                      <li key={i}>{fmtTime(numberOf(e.timeMs))} · {textOf(e.text)}</li>
                    ))}
                  </ul>
                )}
              </div>
            </details>
          )}

          <details open={showDiagnostics} className={styles.collapse}>
            <summary onClick={() => setShowDiagnostics(v => !v)}>诊断结果</summary>
            {diagnostics.length === 0 ? (
              <p className={styles.muted}>点击「运行诊断」执行真实检查（DNS / TCP 7844 / UDP 7844 / Origin / 公网入口）。</p>
            ) : (
              <ul className={styles.diagList}>
                {diagnostics.map((row, i) => (
                  <li key={i}>
                    <span className={booleanOf(row.ok) ? styles.diagOk : styles.diagFail}>{booleanOf(row.ok) ? '✓' : '×'}</span>
                    <span>{textOf(row.name)}</span>
                    <span className={styles.muted}>{textOf(row.detail)}</span>
                  </li>
                ))}
              </ul>
            )}
          </details>

          <details open={showLogs} className={styles.collapse}>
            <summary onClick={() => setShowLogs(v => !v)}>最近事件与日志</summary>
            {logs.length === 0 ? <p className={styles.muted}>日志为空（点击「查看日志」加载）。</p> : (
              <ul className={styles.eventList}>
                {logs.slice(-30).map((l, i) => (
                  <li key={i}>{fmtTime(numberOf(l.timeMs))} · {textOf(l.level)} · {textOf(l.text)}</li>
                ))}
              </ul>
            )}
          </details>

          <details open={showAdvanced} className={styles.collapse}>
            <summary onClick={() => setShowAdvanced(v => !v)}>高级设置</summary>
            <div className={styles.detailRows}>
              <div><span>配置协议</span><strong>{tunnel.configuredProtocol ?? 'auto'}</strong></div>
              <div><span>实际协议</span><strong>{tunnel.connectedProtocol ?? '—'}</strong></div>
            </div>
            <div className={styles.actions}>
              {(['auto', 'http2', 'quic'] as const).map(p => (
                <Button
                  key={p}
                  disabled={busy || isTransition}
                  onClick={() => run({ kind: 'tunnel-protocol', payload: { protocol: p } }) }
                >{p}</Button>
              ))}
            </div>
            <p className={styles.muted}>默认「auto」在移动网络固定 HTTP/2（运营商 NAT 下 QUIC/UDP 不稳定）；仅测试确证后手动选择 quic。</p>
          </details>

          <Form onSubmit={payload => run({ kind: 'web-tunnel', payload: omitBlank(payload, ['cloudflareTunnelToken']) })}>
            <Field label="公开域名"><input className={styles.input} name="cloudflareHostname" defaultValue={textOf(settings.cloudflareHostname)} /></Field>
            <Field label={hasToken ? 'Tunnel Token（已配置，输入新值可替换）' : 'Tunnel Token'}><input className={styles.input} type="password" name="cloudflareTunnelToken" autoComplete="new-password" placeholder={hasToken ? '••••••••' : ''} /></Field>
            <label className={styles.check}><input type="checkbox" name="cloudflareTunnelEnabled" defaultChecked={booleanOf(settings.cloudflareTunnelEnabled)} /> 启用隧道</label>
            <Button type="submit" primary disabled={busy}>保存配置</Button>
            <Button disabled={busy} onClick={() => run({ kind: 'web-tunnel-install' })}>安装/更新 cloudflared</Button>
          </Form>
        </Card>
      </Grid>
      <div className={styles.actions}>
        <Button disabled={busy} onClick={() => { if (confirm('重启 Web 服务？当前连接会短暂断开。')) run({ kind: 'web-restart' }) }}>重启 Web 服务</Button>
        <Button danger disabled={busy} onClick={() => run({ kind: 'logout' })}>退出登录</Button>
      </div>
    </>
  )
}
