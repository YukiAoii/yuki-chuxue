package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.Persona

/**
 * 「选人设」弹窗的搜索（v0.61.21）。
 *
 * ## 为什么单独一个文件、且是纯函数
 * 它原本写在 Composable 里 —— 而"搜出来哪些人设"是**行为**，不是样式。
 * 抽出来之后它能被 JVM 单测钉住，UI 那层就只剩"把它画出来"。
 * （这台机器没有设备也没有 Compose 测试基建：写在 Composable 里的逻辑
 *   等于永远没人验过 —— 抽出来是唯一能拿到证据的办法。）
 *
 * ⚠️ 与 `PersonaScreen.kt` 里那个**列表页**的 `filterPersonas` 是两件事：
 *    那个搜的是人设管理页的整页列表，这个搜的是"开始新对话"时选人的弹窗。
 *    两者范围一样宽，但**改一个不该动另一个**，所以名字与文件都分开。
 *    （同名会让 Kotlin 重载决议二义 —— 已经踩过一次。）
 *
 * ## 搜的范围刻意宽
 * 名字 / 角色名 / 备注 / 设定正文**都算**：用户未必记得那个词写在哪一栏，
 * 而"我明明写了却搜不到"比"搜出几个无关的"难受得多。
 *
 * @param keyword 空串或纯空白 = 不过滤（返回全部，保持原有顺序）
 */
fun filterPersonasForPicker(personas: List<Persona>, keyword: String): List<Persona> {
    val k = keyword.trim().lowercase()
    if (k.isEmpty()) return personas
    return personas.filter { p ->
        p.displayName.lowercase().contains(k) ||
            p.roleName.lowercase().contains(k) ||
            p.note.lowercase().contains(k) ||
            p.customPrompt.lowercase().contains(k)
    }
}
