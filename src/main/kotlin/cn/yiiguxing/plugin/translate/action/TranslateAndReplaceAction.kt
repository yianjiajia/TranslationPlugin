package cn.yiiguxing.plugin.translate.action

import cn.yiiguxing.plugin.translate.trans.Lang
import cn.yiiguxing.plugin.translate.trans.TranslateListener
import cn.yiiguxing.plugin.translate.trans.Translation
import cn.yiiguxing.plugin.translate.ui.SpeedSearchListPopupStep
import cn.yiiguxing.plugin.translate.ui.showListPopup
import cn.yiiguxing.plugin.translate.util.*
import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.lookup.LookupAdapter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.textarea.TextComponentEditor
import com.intellij.openapi.editor.textarea.TextComponentEditorImpl
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.lang.ref.WeakReference
import java.util.*
import javax.swing.text.JTextComponent

/**
 * 翻译并替换
 */
class TranslateAndReplaceAction : AutoSelectAction(true, NON_WHITESPACE_CONDITION) {

    init {
        isEnabledInModalContext = true
    }

    override val selectionMode: SelectionMode
        get() = Settings.autoSelectionMode

    override val AnActionEvent.editor: Editor?
        get() {
            val dataContext = dataContext
            val editor = CommonDataKeys.EDITOR.getData(dataContext)
            return if (editor != null) {
                editor
            } else {
                val data = PlatformDataKeys.CONTEXT_COMPONENT.getData(dataContext)
                if (data is JTextComponent) {
                    val project = CommonDataKeys.PROJECT.getData(dataContext)
                    TextComponentEditorImpl(project, data)
                } else null
            }
        }

    override fun onUpdate(e: AnActionEvent): Boolean {
        val editor = e.editor ?: return false
        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            return selectionModel.selectedText?.any(JAVA_IDENTIFIER_PART_CONDITION) ?: false
        }
        return super.onUpdate(e)
    }

    override fun onActionPerformed(e: AnActionEvent, editor: Editor, selectionRange: TextRange) {
        val project = e.project ?: return
        e.getData(PlatformDataKeys.VIRTUAL_FILE)?.let {
            if (it.isReadOnly(project)) {
                return
            }
        }

        val language = e.getData(LangDataKeys.LANGUAGE)
        val editorRef = WeakReference(editor)
        editor.document.getText(selectionRange)
            .takeIf { it.isNotBlank() && it.any(JAVA_IDENTIFIER_PART_CONDITION) }
            ?.let { text ->
                fun translate(targetLang: Lang) {
                    TranslateService.translate(text, Lang.AUTO, targetLang, object : TranslateListener {
                        override fun onSuccess(translation: Translation) {
                            val primaryLanguage = TranslateService.translator.primaryLanguage
                            if (translation.srcLang == Lang.ENGLISH
                                && primaryLanguage != Lang.ENGLISH
                                && targetLang == Lang.ENGLISH
                            ) {
                                translate(primaryLanguage)
                            } else {
                                val items = translation
                                    .run {
                                        dictionaries
                                            .map { it.terms }
                                            .flatten()
                                            .toMutableSet()
                                            .apply { trans?.let { add(it) } }
                                    }
                                    .asSequence()
                                    .filter { it.isNotEmpty() }
                                    .map { it.fixWhitespace() }
                                    .toList()

                                val elementsToReplace = createReplaceElements(language, items, translation.targetLang)
                                editorRef.get()?.let { e ->
                                    invokeLater {
                                        if (e is TextComponentEditor) {
                                            e.showListPopup(selectionRange, text, elementsToReplace)
                                        } else {
                                            e.showLookup(selectionRange, text, elementsToReplace)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onError(message: String, throwable: Throwable) {
                            editorRef.get()?.let { editor ->
                                invokeLater {
                                    editor.showLookup(selectionRange, text, emptyList())
                                }
                                Notifications.showErrorNotification(
                                    editor.project,
                                    NOTIFICATION_DISPLAY_ID,
                                    "Translate and Replace", message, throwable
                                )
                            }
                        }
                    })
                }

                val targetLang = Lang.AUTO
                    .takeIf { TranslateService.translator.supportedTargetLanguages.contains(it) }
                    ?: Lang.ENGLISH
                translate(targetLang)
            }
    }

    private companion object {

        const val NOTIFICATION_DISPLAY_ID = "Translate and Replace Error"

        /** 谷歌翻译的空格符：`0xA0` */
        const val GT_WHITESPACE_CHARACTER = ' ' // 0xA0
        /** 空格符：`0x20` */
        const val WHITESPACE_CHARACTER = ' ' // 0x20

        val HIGHLIGHT_ATTRIBUTES = TextAttributes().apply {
            effectType = EffectType.BOXED
            effectColor = JBColor(0xFF0000, 0xFF0000)
        }

        fun String.fixWhitespace() = replace(GT_WHITESPACE_CHARACTER, WHITESPACE_CHARACTER)

        fun VirtualFile.isReadOnly(project: Project): Boolean {
            return ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(this).hasReadonlyFiles()
        }

        fun Editor.canShowPopup(selectionRange: TextRange, targetText: String): Boolean {
            return !isDisposed
                    && targetText == document.getText(selectionRange)
                    && selectionRange.containsOffset(caretModel.offset)
        }

        fun Editor.tryReplace(selectionRange: TextRange, elementsToReplace: List<String>): Boolean {
            return if (elementsToReplace.size == 1 && Settings.autoReplace) {
                replaceText(selectionRange, elementsToReplace.first())
                true
            } else false
        }

        fun Editor.checkSelection(selectionRange: TextRange): Boolean {
            val startOffset = selectionRange.startOffset
            val endOffset = selectionRange.endOffset
            with(selectionModel) {
                if (hasSelection()) {
                    if (selectionStart != startOffset || selectionEnd != endOffset) {
                        return false
                    }
                } else {
                    setSelection(startOffset, endOffset)
                }
            }

            scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            caretModel.moveToOffset(endOffset)

            return true
        }

        fun Editor.showLookup(selectionRange: TextRange, targetText: String, elementsToReplace: List<String>) {
            if (!canShowPopup(selectionRange, targetText)) {
                return
            }
            if (tryReplace(selectionRange, elementsToReplace)) {
                return
            }
            if (!checkSelection(selectionRange)) {
                return
            }

            val project = project ?: return
            val lookupElements = elementsToReplace.map(LookupElementBuilder::create).toTypedArray()
            val lookup = LookupManager.getInstance(project).showLookup(this, *lookupElements) ?: return
            val highlightManager = HighlightManager.getInstance(project)
            val highlighters = highlightManager.addHighlight(this, selectionRange)

            lookup.addLookupListener(object : LookupAdapter() {
                override fun itemSelected(event: LookupEvent) {
                    highlightManager.removeSegmentHighlighters(this@showLookup, highlighters)
                }

                override fun lookupCanceled(event: LookupEvent) {
                    selectionModel.removeSelection()
                    highlightManager.removeSegmentHighlighters(this@showLookup, highlighters)
                }
            })
        }

        fun Editor.replaceText(range: TextRange, text: String) {
            ApplicationManager.getApplication().runWriteAction {
                document.startGuardedBlockChecking()
                try {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.replaceString(range.startOffset, range.endOffset, text)
                    }
                    selectionModel.removeSelection()
                    caretModel.moveToOffset(range.startOffset + text.length)
                    scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                } finally {
                    document.stopGuardedBlockChecking()
                }
            }
        }

        fun HighlightManager.addHighlight(
            editor: Editor,
            selectionRange: TextRange
        ): List<RangeHighlighter> = ArrayList<RangeHighlighter>().apply {
            addOccurrenceHighlight(
                editor,
                selectionRange.startOffset,
                selectionRange.endOffset,
                HIGHLIGHT_ATTRIBUTES,
                0,
                this,
                null
            )

            for (highlighter in this) {
                highlighter.isGreedyToLeft = true
                highlighter.isGreedyToRight = true
            }
        }

        fun HighlightManager.removeSegmentHighlighters(editor: Editor, highlighters: List<RangeHighlighter>) {
            highlighters.forEach { removeSegmentHighlighter(editor, it) }
        }

        fun TextComponentEditor.showListPopup(selectionRange: TextRange, targetText: String, elements: List<String>) {
            if (!canShowPopup(selectionRange, targetText)) {
                return
            }
            if (tryReplace(selectionRange, elements)) {
                return
            }
            if (!checkSelection(selectionRange)) {
                return
            }

            val step = object : SpeedSearchListPopupStep<String>(elements) {
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    replaceText(selectionRange, selectedValue)
                    return super.onChosen(selectedValue, true)
                }
            }
            showListPopup(step, 10)
        }

        fun createReplaceElements(language: Language?, items: List<String>, targetLang: Lang): List<String> {
            if (items.isEmpty()) {
                return emptyList()
            }

            if (targetLang != Lang.ENGLISH || language == PlainTextLanguage.INSTANCE) {
                return items
            }

            val camel = LinkedHashSet<String>()
            val pascal = LinkedHashSet<String>()
            val original = LinkedHashSet<String>()

            val camelBuilder = StringBuilder()
            val pascalBuilder = StringBuilder()

            val lowerWithSeparatorBuilders = Settings.separators.map { it to StringBuilder() }
            val lowerWithSeparator = Settings.separators.map { it to LinkedHashSet<String>() }.toMap()

            for (item in items) {
                original.add(item)
                if (item.length > 50) {
                    continue
                }

                val words: List<String> = StringUtil.getWordsIn(item)
                if (words.isEmpty()) {
                    continue
                }

                camelBuilder.setLength(0)
                pascalBuilder.setLength(0)
                lowerWithSeparatorBuilders.forEach { it.second.setLength(0) }

                build(words, camelBuilder, pascalBuilder, lowerWithSeparatorBuilders)

                camel.add(camelBuilder.toString())
                pascal.add(pascalBuilder.toString())
                for ((separator, builder) in lowerWithSeparatorBuilders) {
                    lowerWithSeparator.getValue(separator).add(builder.toString())
                }
            }

            return LinkedHashSet<String>()
                .apply {
                    addAll(camel)
                    addAll(pascal)
                    for ((_, elements) in lowerWithSeparator) {
                        addAll(elements)
                    }
                    addAll(original)
                }
                .toList()
        }

        fun build(
            words: List<String>,
            camel: StringBuilder,
            pascal: StringBuilder,
            lowerWithSeparator: List<Pair<Char, StringBuilder>>
        ) {
            for (i in words.indices) {
                val word = if (i == 0) words[i].sanitizeJavaIdentifierStart() else words[i]
                val lowerCase = word.toLowerCase()

                for ((separator, builder) in lowerWithSeparator) {
                    if (i > 0) {
                        builder.append(separator)
                    }
                    builder.append(lowerCase)
                }

                val capitalized = StringUtil.capitalizeWithJavaBeanConvention(word)
                camel.append(if (i == 0) lowerCase else capitalized)
                pascal.append(capitalized)
            }
        }

        fun String.sanitizeJavaIdentifierStart(): String {
            return if (Character.isJavaIdentifierStart(this[0])) this else "_$this"
        }
    }
}
