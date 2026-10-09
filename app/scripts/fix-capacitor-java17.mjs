#!/usr/bin/env node
/**
 * 把 Capacitor 硬编码的 Java 21 降为 17，以匹配本机 JDK。
 *
 * 为什么需要这个脚本：
 *   Capacitor 7 生成的构建配置要求 Java 21（sourceCompatibility JavaVersion.VERSION_21），
 *   但本机安装的是 JDK 17（C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot），
 *   直接构建会失败：`错误: 无效的源发行版：21`。
 *
 *   这些配置写在 node_modules 里，而 node_modules **不纳入版本管理**，
 *   所以每次 `npm install` 后都会被重置回 21 —— 用脚本固化，避免反复手改。
 *
 * 更彻底的替代方案：安装 JDK 21 并把 JAVA_HOME 指向它，则不需要本脚本。
 *   本机当前只有 17，故采用降级方案。
 *
 * 用法：node scripts/fix-capacitor-java17.mjs
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

/** 需要降级的文件（相对 app/ 目录） */
const TARGETS = [
  'node_modules/@capacitor/android/capacitor/build.gradle',
  'node_modules/@capacitor/cli/dist/android/update.js',
]

/** Capitor 生成到 android/ 下、cap sync 时会被重写的文件 */
const GENERATED = [
  'android/app/capacitor.build.gradle',
  'android/capacitor-cordova-android-plugins/build.gradle',
]

const FROM = 'JavaVersion.VERSION_21'
const TO = 'JavaVersion.VERSION_17'

let changed = 0
let checked = 0

function fix(relPath) {
  const abs = join(root, relPath)
  if (!existsSync(abs)) return
  checked++
  const before = readFileSync(abs, 'utf8')
  if (!before.includes(FROM)) return
  const after = before.split(FROM).join(TO)
  writeFileSync(abs, after, 'utf8')
  changed++
  console.log(`  ✔ ${relPath}`)
}

console.log('把 Capacitor 的 Java 21 降到 17（匹配本机 JDK）...')
for (const t of [...TARGETS, ...GENERATED]) fix(t)

if (changed === 0) {
  console.log(`  无需改动（检查了 ${checked} 个文件）`)
} else {
  console.log(`  共修改 ${changed} 个文件`)
}
