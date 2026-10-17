
package com.yadino.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private data class Task(
        val id: String = UUID.randomUUID().toString(),
        var text: String,
        var done: Boolean = false
    )

    private data class Note(
        val id: String = UUID.randomUUID().toString(),
        var title: String,
        var body: String,
        var category: String,
        var isPrivate: Boolean,
        val tasks: MutableList<Task> = mutableListOf()
    )

    private val categories = listOf(
        "عمومی", "کار", "شخصی", "خرید", "درس", "ایده‌ها"
    )

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var privateCheck: CheckBox
    private lateinit var notesContainer: LinearLayout
    private lateinit var statusText: TextView

    private val notes = mutableListOf<Note>()
    private var speechTarget: EditText? = null

    companion object {
        private const val TAG = "YadinoStartup"
        private const val SPEECH_REQUEST = 401
        private const val DATA_KEY = "notes_json"
        private const val PIN_KEY = "pin_hash"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            initializeSecureStorage()
            loadNotes()
            buildScreen()
            renderNotes()
        } catch (e: Exception) {
            Log.e(TAG, "Application startup failed", e)
            showStartupError(e)
        }
    }

    private fun initializeSecureStorage() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            this,
            "yadino_secure_storage",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun showStartupError(error: Exception) {
        val details = buildString {
            appendLine("یادینو نتوانست راه‌اندازی شود.")
            appendLine()
            appendLine("نوع خطا:")
            appendLine(error.javaClass.simpleName)
            appendLine()
            appendLine("شرح خطا:")
            appendLine(error.localizedMessage ?: "توضیحی ارائه نشده است.")
            appendLine()
            append("گزارش خطا با برچسب YadinoStartup ثبت شده است.")
        }

        val message = TextView(this).apply {
            text = details
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

        val scroll = ScrollView(this).apply {
            addView(message)
        }

        AlertDialog.Builder(this)
            .setTitle("خطای راه‌اندازی یادینو")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("بستن برنامه") { _, _ -> finish() }
            .show()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        val header = TextView(this).apply {
            text = "یادینو"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFF263A73.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(header, matchWrap())

        val subtitle = TextView(this).apply {
            text = "یادداشت‌ها و کارهایت را مرتب کن"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF606A7B.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(subtitle, matchWrap())

        titleInput = editText("عنوان یادداشت")
        root.addView(titleInput, matchWrap())

        categorySpinner = Spinner(this)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            categories
        )
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )
        categorySpinner.adapter = adapter
        root.addView(categorySpinner, matchWrap())

        bodyInput = editText("متن یادداشت...")
        bodyInput.minLines = 3
        bodyInput.gravity = Gravity.TOP or Gravity.START
        root.addView(bodyInput, matchWrap())

        val voiceButton = button("🎙 تبدیل گفتار به متن")
        voiceButton.setOnClickListener {
            speechTarget = bodyInput
            startSpeechRecognition()
        }
        root.addView(voiceButton, matchWrap())

        privateCheck = CheckBox(this).apply {
            text = "یادداشت خصوصی (محافظت با رمز)"
            textSize = 15f
        }
        root.addView(privateCheck, matchWrap())

        val taskTitle = TextView(this).apply {
            text = "کارهای این یادداشت"
            textSize = 18f
            setTextColor(0xFF263A73.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(taskTitle, matchWrap())

        taskInput = editText("کار جدید؛ مثلاً خرید نان")
        root.addView(taskInput, matchWrap())

        val taskVoiceButton = button("🎙 گفتن کار با صدا")
        taskVoiceButton.setOnClickListener {
            speechTarget = taskInput
            startSpeechRecognition()
        }
        root.addView(taskVoiceButton, matchWrap())

        val saveButton = button("＋ ذخیره یادداشت")
        saveButton.setOnClickListener { saveCurrentNote() }
        root.addView(saveButton, matchWrap())

        val aiButton = button("✨ دستیار هوشمند آنلاین")
        aiButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("دستیار هوشمند یادینو")
                .setMessage(
                    "قابلیت‌های اصلی یادینو به‌صورت محلی کار می‌کنند. " +
                    "برای فعال شدن هوش مصنوعی آنلاین باید سرویس هوش مصنوعی " +
                    "و یک ارتباط امن سمت سرور به پروژه اضافه شود."
                )
                .setPositiveButton("متوجه شدم", null)
                .show()
        }
        root.addView(aiButton, matchWrap())

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText, matchWrap())

        val savedTitle = TextView(this).apply {
            text = "یادداشت‌های ذخیره‌شده"
            textSize = 21f
            setTextColor(0xFF263A73.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(savedTitle, matchWrap())

        val scroll = ScrollView(this)
        notesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(notesContainer)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        updateStatus()
    }

    private fun saveCurrentNote() {
        val title = titleInput.text.toString().trim()
        val body = bodyInput.text.toString().trim()
        val taskText = taskInput.text.toString().trim()
        val category = categorySpinner.selectedItem?.toString() ?: "عمومی"
        val isPrivate = privateCheck.isChecked

        if (title.isBlank() && body.isBlank() && taskText.isBlank()) {
            Toast.makeText(
                this,
                "اول عنوان، متن یا یک کار وارد کن",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isPrivate && prefs.getString(PIN_KEY, null) == null) {
            showSetPinDialog {
                createNote(title, body, category, isPrivate, taskText)
            }
        } else {
            createNote(title, body, category, isPrivate, taskText)
        }
    }

    private fun createNote(
        title: String,
        body: String,
        category: String,
        isPrivate: Boolean,
        taskText: String
    ) {
        val note = Note(
            title = title.ifBlank { "یادداشت بدون عنوان" },
            body = body,
            category = category,
            isPrivate = isPrivate
        )

        if (taskText.isNotBlank()) {
            splitTasks(taskText).forEach {
                note.tasks.add(Task(text = it))
            }
        }

        notes.add(0, note)
        saveNotes()
        renderNotes()

        titleInput.text.clear()
        bodyInput.text.clear()
        taskInput.text.clear()
        privateCheck.isChecked = false
        categorySpinner.setSelection(0)

        Toast.makeText(this, "یادداشت ذخیره شد ❤️", Toast.LENGTH_SHORT).show()
    }

    private fun splitTasks(text: String): List<String> {
        return text
            .replace("برو سر خط بعدی", "\n", ignoreCase = true)
            .replace("خط بعدی", "\n", ignoreCase = true)
            .replace("کار بعدی", "\n", ignoreCase = true)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                it.removePrefix("•").trim()
                    .replace(Regex("^\\d+[.)\\-،]\\s*"), "")
            }
            .filter { it.isNotBlank() }
    }

    private fun renderNotes() {
        notesContainer.removeAllViews()

        if (notes.isEmpty()) {
            val empty = TextView(this).apply {
                text = "هنوز یادداشتی نداری. اولین یادداشتت رو بساز!"
                textSize = 15f
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setTextColor(0xFF64748B.toInt())
            }
            notesContainer.addView(empty)
            updateStatus()
            return
        }

        notes.forEach { note ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }

            val cardParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }

            val heading = TextView(this).apply {
                text = if (note.isPrivate) "🔒 ${note.title}" else note.title
                textSize = 19f
                setTextColor(0xFF263A73.toInt())
            }
            card.addView(heading)

            val category = TextView(this).apply {
                text = "دسته‌بندی: ${note.category}"
                textSize = 13f
                setTextColor(0xFF64748B.toInt())
                setPadding(0, dp(3), 0, dp(6))
            }
            card.addView(category)

            if (note.isPrivate) {
                val lockedMessage = TextView(this).apply {
                    text = "محتوای این یادداشت خصوصی است."
                    textSize = 14f
                    setTextColor(0xFF475569.toInt())
                }
                card.addView(lockedMessage)

                val unlockButton = button("🔓 باز کردن یادداشت")
                unlockButton.setOnClickListener {
                    showVerifyPinDialog { showPrivateNote(note) }
                }
                card.addView(unlockButton)
            } else {
                showNoteContent(card, note)
            }

            val deleteButton = button("حذف یادداشت")
            deleteButton.setOnClickListener { confirmDelete(note) }
            card.addView(deleteButton)

            notesContainer.addView(card, cardParams)
        }

        updateStatus()
    }

    private fun showNoteContent(card: LinearLayout, note: Note) {
        if (note.body.isNotBlank()) {
            val body = TextView(this).apply {
                text = note.body
                textSize = 15f
                setTextColor(0xFF334155.toInt())
                setPadding(0, dp(4), 0, dp(8))
            }
            card.addView(body)
        }

        renderTasks(card, note)
    }

    private fun showPrivateNote(note: Note) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val body = TextView(this).apply {
            text = note.body.ifBlank { "متنی ثبت نشده است." }
            textSize = 16f
        }
        container.addView(body)
        renderTasks(container, note)

        AlertDialog.Builder(this)
            .setTitle("🔒 ${note.title}")
            .setView(container)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun renderTasks(container: LinearLayout, note: Note) {
        if (note.tasks.isEmpty()) return

        val progress = TextView(this).apply {
            text = "پیشرفت: ${note.tasks.count { it.done }} از ${note.tasks.size} کار انجام شده"
            textSize = 14f
            setTextColor(0xFF25634A.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(progress)

        note.tasks.forEachIndexed { index, task ->
            val checkbox = CheckBox(this).apply {
                text = "${index + 1}. ${task.text}"
                isChecked = task.done
                textSize = 14f
                setOnCheckedChangeListener { _, checked ->
                    task.done = checked
                    saveNotes()
                    renderNotes()
                }
            }
            container.addView(checkbox)
        }
    }

    private fun confirmDelete(note: Note) {
        val action = {
            notes.removeAll { it.id == note.id }
            saveNotes()
            renderNotes()
            Toast.makeText(this, "یادداشت حذف شد", Toast.LENGTH_SHORT).show()
        }

        if (note.isPrivate) {
            showVerifyPinDialog {
                AlertDialog.Builder(this)
                    .setTitle("حذف یادداشت")
                    .setMessage("این یادداشت برای همیشه حذف شود؟")
                    .setNegativeButton("لغو", null)
                    .setPositiveButton("حذف") { _, _ -> action() }
                    .show()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("حذف یادداشت")
                .setMessage("این یادداشت برای همیشه حذف شود؟")
                .setNegativeButton("لغو", null)
                .setPositiveButton("حذف") { _, _ -> action() }
                .show()
        }
    }

    private fun showSetPinDialog(onSuccess: () -> Unit) {
        val pinInput = EditText(this).apply {
            hint = "رمز عددی، حداقل ۴ رقم"
            inputType = 2
        }

        AlertDialog.Builder(this)
            .setTitle("ساخت رمز یادداشت خصوصی")
            .setMessage("این رمز برای باز کردن یادداشت‌های خصوصی استفاده می‌شود.")
            .setView(pinInput)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره رمز") { _, _ ->
                val pin = pinInput.text.toString()
                if (pin.length < 4 || pin.any { !it.isDigit() }) {
                    Toast.makeText(
                        this,
                        "رمز باید حداقل ۴ رقم باشد",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    prefs.edit().putString(PIN_KEY, hashPin(pin)).apply()
                    onSuccess()
                }
            }
            .show()
    }

    private fun showVerifyPinDialog(onSuccess: () -> Unit) {
        val pinInput = EditText(this).apply {
            hint = "رمز خصوصی"
            inputType = 2
        }

        AlertDialog.Builder(this)
            .setTitle("باز کردن یادداشت خصوصی")
            .setView(pinInput)
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ ->
                val savedHash = prefs.getString(PIN_KEY, null)
                if (savedHash != null && savedHash == hashPin(pinInput.text.toString())) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "رمز اشتباه است", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "فارسی صحبت کن...")
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognition could not start", e)
            Toast.makeText(
                this,
                "تشخیص گفتار در این گوشی در دسترس نیست.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Uses the speech recognition activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SPEECH_REQUEST && resultCode == Activity.RESULT_OK) {
            val spoken = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (spoken.isNotBlank()) {
                val target = speechTarget ?: bodyInput
                val existing = target.text.toString()

                val processed = spoken
                    .replace("برو سر خط بعدی", "\n", ignoreCase = true)
                    .replace("خط بعدی", "\n", ignoreCase = true)

                target.setText(
                    if (existing.isBlank()) processed
                    else "$existing\n$processed"
                )
                target.setSelection(target.text.length)
            }
        }
    }

    private fun saveNotes() {
        val array = JSONArray()

        notes.forEach { note ->
            val taskArray = JSONArray()

            note.tasks.forEach { task ->
                taskArray.put(
                    JSONObject().apply {
                        put("id", task.id)
                        put("text", task.text)
                        put("done", task.done)
                    }
                )
            }

            array.put(
                JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("body", note.body)
                    put("category", note.category)
                    put("private", note.isPrivate)
                    put("tasks", taskArray)
                }
            )
        }

        prefs.edit().putString(DATA_KEY, array.toString()).apply()
    }

    private fun loadNotes() {
        notes.clear()
        val raw = prefs.getString(DATA_KEY, null) ?: return

        try {
            val array = JSONArray(raw)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val note = Note(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", "یادداشت"),
                    body = obj.optString("body", ""),
                    category = obj.optString("category", "عمومی"),
                    isPrivate = obj.optBoolean("private", false)
                )

                val taskArray = obj.optJSONArray("tasks") ?: JSONArray()

                for (j in 0 until taskArray.length()) {
                    val taskObj = taskArray.getJSONObject(j)
                    note.tasks.add(
                        Task(
                            id = taskObj.optString("id", UUID.randomUUID().toString()),
                            text = taskObj.optString("text", ""),
                            done = taskObj.optBoolean("done", false)
                        )
                    )
                }

                notes.add(note)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read saved notes", e)
            Toast.makeText(
                this,
                "خواندن یادداشت‌ها با خطا روبه‌رو شد.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateStatus() {
        val allTasks = notes.flatMap { it.tasks }
        statusText.text =
            "یادداشت‌ها: ${notes.size}  |  کارهای انجام‌شده: ${allTasks.count { it.done }} از ${allTasks.size}"
    }

    private fun editText(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 15f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
    }

    private fun button(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(5)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
