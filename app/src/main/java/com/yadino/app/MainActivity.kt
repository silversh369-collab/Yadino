package com.yadino.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var root: LinearLayout
    private lateinit var input: EditText
    private lateinit var notesContainer: LinearLayout
    private lateinit var tts: TextToSpeech

    private val prefs by lazy {
        getSharedPreferences("yadino_notes", MODE_PRIVATE)
    }

    private val notes = mutableListOf<String>()
    private var ttsReady = false

    private val speechLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )
                    ?.firstOrNull()

                if (!spoken.isNullOrBlank()) {
                    input.setText(spoken)
                    input.setSelection(input.text.length)
                }
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startSpeechRecognition()
            } else {
                toast("برای ورودی صوتی اجازهٔ میکروفون لازم است.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        loadNotes()
        buildInterface()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("fa", "IR"))
            ttsReady =
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(12))
            setBackgroundColor(Color.rgb(245, 247, 252))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "یادینو"
            textSize = 30f
            setTextColor(Color.rgb(44, 65, 130))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "دستیار یادداشت‌های تو"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }
        root.addView(subtitle, matchWrap())

        input = EditText(this).apply {
            hint = "یادداشتت رو اینجا بنویس..."
            textSize = 16f
            gravity = Gravity.TOP or Gravity.RIGHT
            minLines = 3
            maxLines = 5
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, 14)
        }

        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        )

        addButton("🎙️ گفتن یادداشت") {
            if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startSpeechRecognition()
            } else {
                audioPermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            }
        }

        addButton("💾 ذخیرهٔ یادداشت") {
            saveNote()
        }

        addButton("🔊 خواندن متن با صدا") {
            val text = input.text.toString().trim()

            if (text.isNotEmpty()) {
                if (ttsReady) {
                    tts.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "yadino_read"
                    )
                } else {
                    toast("صدای فارسی روی دستگاه آماده نیست.")
                }
            } else {
                toast("اول متنی بنویس.")
            }
        }

        val heading = TextView(this).apply {
            text = "یادداشت‌های ذخیره‌شده"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 48, 70))
            setPadding(0, dp(18), 0, dp(10))
        }
        root.addView(heading, matchWrap())

        val scroll = ScrollView(this)

        notesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(
            notesContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        renderNotes()
    }

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(66, 91, 190), 12)
            setOnClickListener {
                action()
            }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {
            topMargin = dp(9)
        }

        root.addView(button, params)
    }

    private fun saveNote() {
        val text = input.text.toString().trim()

        if (text.isEmpty()) {
            toast("اول یک یادداشت بنویس.")
            return
        }

        notes.add(0, text)
        persistNotes()
        renderNotes()
        input.text.clear()
        toast("یادداشت ذخیره شد ❤️")
    }

    private fun loadNotes() {
        val saved = prefs.getString("items", "[]") ?: "[]"

        try {
            val array = JSONArray(saved)

            for (i in 0 until array.length()) {
                notes.add(array.getString(i))
            }
        } catch (_: Exception) {
            notes.clear()
        }
    }

    private fun persistNotes() {
        val array = JSONArray()

        notes.forEach { note ->
            array.put(note)
        }

        prefs.edit()
            .putString("items", array.toString())
            .apply()
    }

    private fun renderNotes() {
        if (!::notesContainer.isInitialized) return

        notesContainer.removeAllViews()

        if (notes.isEmpty()) {
            val empty = TextView(this).apply {
                text = "هنوز یادداشتی نداری."
                textSize = 15f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            }

            notesContainer.addView(empty, matchWrap())
            return
        }

        notes.forEachIndexed { index, note ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(8))
                background = rounded(Color.WHITE, 12)
            }

            val content = TextView(this).apply {
                text = note
                textSize = 16f
                setTextColor(Color.rgb(35, 40, 55))
            }

            card.addView(content, matchWrap())

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val readButton = Button(this).apply {
                text = "🔊 خواندن"
                isAllCaps = false

                setOnClickListener {
                    if (ttsReady) {
                        tts.speak(
                            note,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "note_$index"
                        )
                    } else {
                        toast("صدای فارسی روی دستگاه آماده نیست.")
                    }
                }
            }

            val deleteButton = Button(this).apply {
                text = "🗑 حذف"
                isAllCaps = false

                setOnClickListener {
                    notes.removeAt(index)
                    persistNotes()
                    renderNotes()
                }
            }

            actions.addView(readButton)
            actions.addView(deleteButton)
            card.addView(actions, matchWrap())

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }

            notesContainer.addView(card, params)
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "fa-IR"
            )
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "صحبت کن..."
            )
        }

        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            toast("برنامهٔ تشخیص گفتار روی دستگاه در دسترس نیست.")
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}