package ua.screentranslator

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions

/** Спільні налаштування перекладу: англійська → українська. */
object TranslatorConfig {
    val options: TranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.UKRAINIAN)
        .build()

    fun isModelDownloaded(): Task<Boolean> =
        RemoteModelManager.getInstance().isModelDownloaded(
            TranslateRemoteModel.Builder(TranslateLanguage.UKRAINIAN).build()
        )
}
