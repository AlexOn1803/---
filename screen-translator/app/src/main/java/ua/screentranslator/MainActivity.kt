package ua.screentranslator

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator

/** Екран налаштування: модель перекладу, увімкнення служби, інструкція і тест. */
class MainActivity : Activity() {

    private lateinit var modelStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var testOutput: TextView

    private lateinit var translator: Translator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        translator = Translation.getClient(TranslatorConfig.options)

        modelStatus = findViewById(R.id.model_status)
        serviceStatus = findViewById(R.id.service_status)
        downloadButton = findViewById(R.id.btn_download)
        testOutput = findViewById(R.id.test_output)
        val testInput = findViewById<EditText>(R.id.test_input)

        downloadButton.setOnClickListener { downloadModel() }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }

        findViewById<Button>(R.id.btn_translate).setOnClickListener {
            val text = testInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            testOutput.text = getString(R.string.translating)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .onSuccessTask { translator.translate(text) }
                .addOnSuccessListener { result ->
                    testOutput.text = result
                    refreshModelStatus()
                }
                .addOnFailureListener { e ->
                    testOutput.text = getString(R.string.model_failed, e.localizedMessage ?: e.toString())
                }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        refreshServiceStatus()
    }

    override fun onDestroy() {
        translator.close()
        super.onDestroy()
    }

    private fun downloadModel() {
        modelStatus.setText(R.string.model_downloading)
        downloadButton.isEnabled = false
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { refreshModelStatus() }
            .addOnFailureListener { e ->
                downloadButton.isEnabled = true
                modelStatus.text = getString(R.string.model_failed, e.localizedMessage ?: e.toString())
            }
    }

    private fun refreshModelStatus() {
        TranslatorConfig.isModelDownloaded()
            .addOnSuccessListener { ready ->
                modelStatus.setText(if (ready) R.string.model_ready else R.string.model_missing)
                downloadButton.visibility = if (ready) View.GONE else View.VISIBLE
                downloadButton.isEnabled = true
            }
            .addOnFailureListener {
                modelStatus.setText(R.string.model_missing)
                downloadButton.visibility = View.VISIBLE
            }
    }

    private fun refreshServiceStatus() {
        serviceStatus.setText(if (isServiceEnabled()) R.string.service_on else R.string.service_off)
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == packageName && info.name == ScreenTranslatorService::class.java.name
            }
    }
}
