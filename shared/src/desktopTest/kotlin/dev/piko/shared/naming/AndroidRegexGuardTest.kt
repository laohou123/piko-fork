package dev.piko.shared.naming

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android 的 java.util.regex 底下是 ICU，不认 Java 专有的 \p{IsXxx}、\p{InXxx}：Android 8 上编译即抛
 * PatternSyntaxException，写在顶层 val 里的正则让整个类初始化失败，1.0.0 在 Android 8 上一解析文件名就崩。
 * 桌面测试跑在 HotSpot 上照常通过，CI 的模拟器是 API 34（NamingUnicodeDigitsSmokeTest 在那里跑解析，但系统太新），都抓不到，只能扫源码。
 * 脚本写 \p{script=Han}，区块写 \p{block=CJKUnifiedIdeographs}，两边都认。
 *
 * \d 也不能用：ICU 的 \d 是全部 Unicode 数字（\p{Nd}），HotSpot 的只是 0-9。文件名里的 𝟐（数学粗体）在 Android 上
 * 被当成数字抓出来，toInt() 解不了，1.1.0 在信息流里一遍历到这样的文件就崩。一律写 [0-9]。
 */
class AndroidRegexGuardTest {
    @Test
    fun `android code uses no java-only unicode property prefixes`() {
        val offenders = androidSources().flatMap { file ->
            file.readLines().withIndex()
                // 注释里提到这种写法不算，说明为什么不用它的注释正需要写出它
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && JAVA_ONLY_PROPERTY.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}" }
        }
        assertTrue(offenders.isEmpty(), "这些正则用了 Android 不认的 \\p{Is…}/\\p{In…}：$offenders")
    }

    @Test
    fun `android code uses no unicode-wide digit class`() {
        val offenders = androidSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && DIGIT_CLASS.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}" }
        }
        assertTrue(offenders.isEmpty(), "这些正则用了 \\d/\\D，Android 上连全角、数学字体的数字也算，改写 [0-9]：$offenders")
    }

    @Test
    fun `regex literals have no bare braces`() {
        val offenders = androidSources().flatMap { file ->
            PATTERN_LITERAL.findAll(file.readText()).mapNotNull { m ->
                val literal = m.groupValues[1].ifEmpty { m.groupValues[2] }
                // Kotlin 的 ${...} 模板会被拼进正则里，先把它们摘掉再判
                val pattern = literal.replace(KOTLIN_TEMPLATE, "")
                if (hasBareBrace(pattern)) {
                    "${file.relativeTo(root).invariantSeparatorsPath} → $pattern"
                } else {
                    null
                }
            }.toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "这些正则含未转义的 { 或 }：Java 的 Pattern 容忍，Android 底下是 ICU，直接抛 PatternSyntaxException；" +
                "写在顶层 val / companion 里更会让整个类初始化失败（翻译一进来就 ExceptionInInitializerError）：$offenders",
        )
    }

    /** 逐字符扫一遍正则：跟踪转义与字符类，找出不是量词、又没转义的花括号。 */
    private fun hasBareBrace(pattern: String): Boolean {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> {
                    // \p{L} 这类属性类自带花括号，是合法的，别当成裸括号
                    val next = pattern.getOrNull(i + 1)
                    if (next == 'p' || next == 'P') {
                        val property = PROPERTY_CLASS.find(pattern, i)
                        if (property != null && property.range.first == i) {
                            i = property.range.last + 1
                            continue
                        }
                    }
                    i += 2
                }
                '[' -> { inClass = true; i += 1 }
                ']' -> { inClass = false; i += 1 }
                '}' -> { if (!inClass) return true; i += 1 }
                '{' -> {
                    if (inClass) { i += 1; continue }
                    val quantifier = QUANTIFIER.find(pattern, i)
                    if (quantifier == null || quantifier.range.first != i) return true
                    i = quantifier.range.last + 1
                }
                else -> i += 1
            }
        }
        return false
    }

    private val root = File("..").canonicalFile

    private fun androidSources(): List<File> {
        val sourceSets = listOf(
            "shared/src/commonMain", "shared/src/jvmSharedMain", "shared/src/androidMain",
            "ui/src/commonMain", "ui/src/androidMain",
            "app/src/main",
        ).map(root::resolve).filter(File::isDirectory)
        assertTrue(sourceSets.isNotEmpty(), "找不到源码目录：${root.absolutePath}")
        return sourceSets.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private companion object {
        // 源码里的反斜杠在原始字符串中是一个，在普通字符串中是两个，两种都算
        val JAVA_ONLY_PROPERTY = Regex("""\\{1,2}[pP]\{(?:Is|In)[A-Z]""")
        val DIGIT_CLASS = Regex("""\\{1,2}[dD]""")

        /** 抓 `Regex("...")` 与 `Regex("""...""")` 里的字面量，两个分组分别对应两种引号。 */
        val PATTERN_LITERAL = Regex(
            """Regex\(\s*(?:"{3}(.*?)"{3}|"((?:[^"\\]|\\.)*)")""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val KOTLIN_TEMPLATE = Regex("""\$\{[^}]*\}""")
        /** 合法量词：{2}、{2,}、{1,4}。 */
        val QUANTIFIER = Regex("""\{[0-9]+(?:,[0-9]*)?\}""")
        /** 合法属性类：\p{L}、\P{Lu} 等，自带花括号但不算裸括号。 */
        val PROPERTY_CLASS = Regex("""\\ [pP]\{[^}]*\}""".replace(" ", ""))
    }
}
