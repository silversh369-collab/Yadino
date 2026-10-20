package com.yadino.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private const val TAG = "Yadino"
private const val STORAGE_NAME = "yadino_secure_storage"
private const val NOTES_KEY = "notes_json"
private const val PIN_KEY = "pin_hash"
private const val CHANNEL_ID = "yadino_reminders"
private const val CHANNEL_ID_SILENT = "yadino_reminders_silent"
private const val AUDIO_PERMISSION_CODE = 7002
private const val NOTIFICATION_PERMISSION_CODE = 7003

private const val REMINDER_NONE_TEXT = "یادآور تنظیم نشده است"
private const val REMINDER_DISABLED_TEXT = "یادآور غیرفعال است"

data class Task(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var done: Boolean = false
)

data class Note(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var body: String,
    var category: String,
    var isPrivate: Boolean = false,
    val tasks: MutableList<Task> = mutableListOf(),
    var reminderAt: Long = 0L,
    var reminderEnabled: Boolean = false,
    var repeatMode: String = "none",
    var repeatCount: Int = 0,
    var repeatIntervalMinutes: Int = 60,
    var ringtoneUri: String = "",
    var reminderFiredCount: Int = 0
)

class MainActivity : AppCompatActivity() {

    // --- داده‌ها ---
    private val categories = listOf(
        "همه دسته‌ها", "عمومی", "کار", "شخصی", "خرید", "درس", "ایده‌ها"
    )
    private val notes = mutableListOf<Note>()

    // --- ویوها ---
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var privateCheckBox: CheckBox
    private lateinit var taskInput: EditText
    private lateinit var notesContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var filterSpinner: Spinner
    private lateinit var reminderSummary: TextView
    private lateinit var securePrefs: android.content.SharedPreferences

    // --- وضعیت یادآور ---
    private var editingNoteId: String? = null
    private var selectedReminderAt: Long = 0L
    private var selectedReminderEnabled = false
    private var selectedRepeatMode = "none"
    private var selectedRepeatCount = 0
    private var selectedRepeatInterval = 60
    private var selectedRingtoneUri = ""

    // --- گفتار ---
    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousSpeech = false
    private var speechTarget: EditText? = null
    private var speechRestartRequested = false
    private var isListening = false
    private var transientErrorCount = 0
    private var pendingVoiceTarget: EditText? = null
    private var pendingVoiceContinuous = false
    
    private var lastFocusedInput: EditText? = null
    private var voiceBaseText = ""
    private var isOfflineMode = false

    private val ringtonePickerLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                selectedRingtoneUri = if (uri == null) "silent" else uri.toString()
            }
            updateReminderSummary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initializeSecureStorage()
            loadNotes()
            createNotificationChannel()
            buildScreen()
            renderNotes()
            requestNotificationPermissionIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Startup failed", e)
            Toast.makeText(this, "خطا در راه‌اندازی: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initializeSecureStorage() {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            this, STORAGE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ===================================================================
    // ساخت UI
    // ===================================================================
    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 20)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        content.addView(TextView(this).apply {
            text = "یادینو"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 18)
        })

        titleInput = EditText(this).apply { hint = "عنوان یادداشت"; textSize = 16f }
        setupFocusTracking(titleInput)
        content.addView(titleInput, matchWidth())

        bodyInput = EditText(this).apply {
            hint = "متن یادداشت"
            minLines = 3
            gravity = Gravity.TOP or Gravity.RIGHT
            textSize = 16f
        }
        setupFocusTracking(bodyInput)
        content.addView(bodyInput, matchWidth())

        categorySpinner = Spinner(this)
        categorySpinner.adapter = spinnerAdapter(categories.drop(1))
        content.addView(categorySpinner, matchWidth())

        privateCheckBox = CheckBox(this).apply { text = "یادداشت خصوصی باشد" }
        content.addView(privateCheckBox)

        taskInput = EditText(this).apply {
            hint = "کارها (هر خط یک کار)"
            textSize = 15f
            minLines = 2
            gravity = Gravity.TOP or Gravity.RIGHT
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        setupFocusTracking(taskInput)
        content.addView(taskInput, matchWidth())

        // دکمه میکروفون یکپارچه
        val voiceBtn = Button(this).apply {
            text = "🎤 شروع ضبط صدا\n(کلیک: آنلاین | نگه‌داشتن: آفلاین)"
            textSize = 14f
            setOnClickListener {
                Toast.makeText(this@MainActivity, "حالت آنلاین", Toast.LENGTH_SHORT).show()
                toggleVoiceInput(false)
            }
            setOnLongClickListener {
                Toast.makeText(this@MainActivity, "حالت آفلاین", Toast.LENGTH_SHORT).show()
                toggleVoiceInput(true)
                true
            }
        }
        content.addView(voiceBtn, matchWidth())

        content.addView(Button(this).apply {
            text = "تنظیم یادآور"
            setOnClickListener { configureReminder() }
        }, matchWidth())

        reminderSummary = TextView(this).apply {
            text = REMINDER_NONE_TEXT
            setPadding(4, 8, 4, 8)
        }
        content.addView(reminderSummary, matchWidth())

        content.addView(Button(this).apply {
            text = "ذخیره یادداشت"
            setOnClickListener { saveCurrentNote() }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "پاک‌کردن فرم"
            setOnClickListener { clearForm() }
        }, matchWidth())

        statusText = TextView(this).apply {
            text = "یادداشت‌ها"
            textSize = 19f
            setPadding(0, 18, 0, 8)
        }
        content.addView(statusText, matchWidth())

        filterSpinner = Spinner(this)
        filterSpinner.adapter = spinnerAdapter(categories)
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderNotes()
            }
        }
        content.addView(filterSpinner, matchWidth())

        notesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(notesContainer, matchWidth())

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun setupFocusTracking(editText: EditText) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lastFocusedInput = editText
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun tasksFromInput(): List<String> =
        taskInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    // ===================================================================
    // ذخیره یادداشت
    // ===================================================================
    private fun saveCurrentNote() {
        val title = titleInput.text.toString().trim()
        val body = bodyInput.text.toString().trim()

        if (title.isBlank() && body.isBlank()) {
            Toast.makeText(this, "عنوان یا متن یادداشت را وارد کن.", Toast.LENGTH_SHORT).show()
            return
        }

        val category = categorySpinner.selectedItem?.toString() ?: "عمومی"
        val isPrivate = privateCheckBox.isChecked

        if (isPrivate && securePrefs.getString(PIN_KEY, null).isNullOrBlank()) {
            showSetPinDialog { persistCurrentNote(title, body, category, isPrivate) }
        } else if (isPrivate) {
            requestPin("برای ذخیره یادداشت خصوصی، PIN را وارد کن") { valid ->
                if (valid) persistCurrentNote(title, body, category, true)
            }
        } else {
            persistCurrentNote(title, body, category, false)
        }
    }

    private fun persistCurrentNote(title: String, body: String, category: String, isPrivate: Boolean) {
        val existing = editingNoteId?.let { id -> notes.find { it.id == id } }
        val note = existing ?: Note(title = title, body = body, category = category)

        note.title = title
        note.body = body
        note.category = category
        note.isPrivate = isPrivate

        val oldDoneByText = note.tasks.associate { it.text to it.done }
        note.tasks.clear()
        tasksFromInput().forEach { text ->
            note.tasks.add(Task(id = UUID.randomUUID().toString(), text = text, done = oldDoneByText[text] ?: false))
        }

        note.reminderAt = selectedReminderAt
        note.reminderEnabled = selectedReminderEnabled
        note.repeatMode = selectedRepeatMode
        note.repeatCount = selectedRepeatCount
        note.repeatIntervalMinutes = selectedRepeatInterval
        note.ringtoneUri = selectedRingtoneUri

        if (existing == null) notes.add(0, note)

        if (note.reminderEnabled && note.reminderAt > System.currentTimeMillis()) {
            scheduleReminder(note)
        } else {
            cancelReminder(note.id)
        }

        saveNotes()
        renderNotes()
        clearForm()
        Toast.makeText(this, "یادداشت ذخیره شد.", Toast.LENGTH_SHORT).show()
    }

    private fun resetReminderSettings() {
        selectedReminderAt = 0L
        selectedReminderEnabled = false
        selectedRepeatMode = "none"
        selectedRepeatCount = 0
        selectedRepeatInterval = 60
        selectedRingtoneUri = ""
        if (::reminderSummary.isInitialized) reminderSummary.text = REMINDER_NONE_TEXT
    }

    private fun clearForm() {
        titleInput.text.clear()
        bodyInput.text.clear()
        taskInput.text.clear()
        categorySpinner.setSelection(0)
        privateCheckBox.isChecked = false
        editingNoteId = null
        resetReminderSettings()
    }

    // ===================================================================
    // رندر لیست یادداشت‌ها (با مخفی‌سازی محتوا)
    // ===================================================================
    private fun renderNotes() {
        if (!::notesContainer.isInitialized) return

        notesContainer.removeAllViews()
        val selectedCategory = if (::filterSpinner.isInitialized) filterSpinner.selectedItem?.toString() ?: "همه دسته‌ها" else "همه دسته‌ها"
        val visibleNotes = notes.filter { selectedCategory == "همه دسته‌ها" || it.category == selectedCategory }
        statusText.text = "یادداشت‌ها (${visibleNotes.size})"

        val strokePx = (1 * resources.displayMetrics.density).toInt()

        visibleNotes.forEach { note ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF2F2F2.toInt())
                    cornerRadius = 18f
                    setStroke(strokePx, 0xFFDDDDDD.toInt())
                }
            }

            // عنوان و دسته‌بندی همیشه دیده می‌شوند
            card.addView(TextView(this).apply {
                text = if (note.isPrivate) "🔒 ${note.title}" else note.title
                textSize = 19f
            }, matchWidth())

            card.addView(TextView(this).apply {
                text = "دسته‌بندی: ${note.category} (برای مشاهده کلیک کنید)"
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            }, matchWidth())

            // کانتینر مخفی برای محتوا
            val detailsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

            if (note.body.isNotBlank()) {
                detailsContainer.addView(TextView(this).apply {
                    text = note.body
                    textSize = 15f
                    setPadding(0, 8, 0, 8)
                }, matchWidth())
            }

            if (note.tasks.isNotEmpty()) {
                val done = note.tasks.count { it.done }
                detailsContainer.addView(TextView(this).apply {
                    text = "پیشرفت کارها: $done از ${note.tasks.size}"
                    textSize = 13f
                }, matchWidth())

                note.tasks.forEach { task ->
                    detailsContainer.addView(CheckBox(this).apply {
                        text = task.text
                        isChecked = task.done
                        setOnCheckedChangeListener { _, checked ->
                            task.done = checked
                            saveNotes()
                            // فقط وضعیت را آپدیت میکنیم بدون بستن کارت
                            (this@apply.parent as? LinearLayout)?.let {
                                // یه رفرش کوچیک
                            }
                        }
                    }, matchWidth())
                }
            }

            if (note.reminderEnabled && note.reminderAt > 0) {
                val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(note.reminderAt)
                detailsContainer.addView(TextView(this).apply {
                    text = "یادآور: $date"
                    textSize = 13f
                }, matchWidth())
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 0)
            }
            actions.addView(Button(this).apply {
                text = "ویرایش"
                setOnClickListener {
                    if (note.isPrivate) {
                        requestPin("برای ویرایش یادداشت خصوصی، PIN را وارد کن") { valid -> if (valid) editNote(note) }
                    } else editNote(note)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            actions.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    val deleteAction = {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("حذف یادداشت").setMessage("این یادداشت حذف شود؟")
                            .setNegativeButton("انصراف", null)
                            .setPositiveButton("حذف") { _, _ ->
                                cancelReminder(note.id)
                                notes.removeAll { it.id == note.id }
                                saveNotes()
                                renderNotes()
                            }.show()
                    }
                    if (note.isPrivate) {
                        requestPin("برای حذف یادداشت خصوصی، PIN را وارد کن") { valid -> if (valid) deleteAction() }
                    } else deleteAction()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            detailsContainer.addView(actions, matchWidth())
            card.addView(detailsContainer, matchWidth())

            // کلیک روی کارت برای باز/بسته کردن
            card.setOnClickListener {
                val toggleDetails = {
                    detailsContainer.visibility = if (detailsContainer.visibility == View.GONE) View.VISIBLE else View.GONE
                }
                if (note.isPrivate) {
                    requestPin("برای مشاهده جزئیات خصوصی، PIN را وارد کن") { valid -> if (valid) toggleDetails() }
                } else {
                    toggleDetails()
                }
            }

            notesContainer.addView(card, matchWidth())
        }
    }

    private fun editNote(note: Note) {
        editingNoteId = note.id
        titleInput.setText(note.title)
        bodyInput.setText(note.body)
        privateCheckBox.isChecked = note.isPrivate

        val categoryIndex = categories.drop(1).indexOf(note.category)
        categorySpinner.setSelection(if (categoryIndex >= 0) categoryIndex else 0)

        taskInput.setText(note.tasks.joinToString("\n") { it.text })

        selectedReminderAt = note.reminderAt
        selectedReminderEnabled = note.reminderEnabled
        selectedRepeatMode = note.repeatMode
        selectedRepeatCount = note.repeatCount
        selectedRepeatInterval = note.repeatIntervalMinutes
        selectedRingtoneUri = note.ringtoneUri

        reminderSummary.text = if (note.reminderEnabled && note.reminderAt > 0) {
            "یادآور فعال: " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(note.reminderAt)
        } else REMINDER_NONE_TEXT

        titleInput.requestFocus()
        Toast.makeText(this, "یادداشت برای ویرایش بارگذاری شد.", Toast.LENGTH_SHORT).show()
    }

    // ===================================================================
    // یادآور
    // ===================================================================
    private fun configureReminder() {
        val calendar = Calendar.getInstance().apply {
            if (selectedReminderAt > System.currentTimeMillis()) timeInMillis = selectedReminderAt
            else {
                add(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        DatePickerDialog(this, { _, year, month, day ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, day)

            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                selectedReminderAt = calendar.timeInMillis
                selectedReminderEnabled = true
                configureRepeatOptions()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun configureRepeatOptions() {
        val choices = arrayOf("بدون تکرار", "هر روز", "هر هفته", "هر ماه", "فاصله زمانی دلخواه", "تعداد دفعات محدود")
        AlertDialog.Builder(this)
            .setTitle("تکرار یادآور")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> { selectedRepeatMode = "none"; selectedRepeatCount = 0; chooseRingtone() }
                    1 -> { selectedRepeatMode = "daily"; chooseRingtone() }
                    2 -> { selectedRepeatMode = "weekly"; chooseRingtone() }
                    3 -> { selectedRepeatMode = "monthly"; chooseRingtone() }
                    4 -> configureCustomInterval()
                    5 -> configureRepeatCount()
                }
            }
            .setNegativeButton("لغو یادآور") { _, _ ->
                selectedReminderEnabled = false
                selectedReminderAt = 0L
                selectedRepeatMode = "none"
                selectedRepeatCount = 0
                reminderSummary.text = REMINDER_DISABLED_TEXT
            }.show()
    }

    private fun configureCustomInterval() {
        val input = EditText(this).apply {
            hint = "فاصله به دقیقه"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(selectedRepeatInterval.toString())
        }
        AlertDialog.Builder(this).setTitle("فاصله تکرار").setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ ->
                val interval = input.text.toString().toIntOrNull() ?: 60
                if (interval < 1) {
                    Toast.makeText(this, "حداقل یک دقیقه.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                selectedRepeatInterval = interval
                selectedRepeatMode = "interval"
                chooseRingtone()
            }.show()
    }

    private fun configureRepeatCount() {
        val input = EditText(this).apply {
            hint = "تعداد تکرار"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
        }
        AlertDialog.Builder(this).setTitle("تعداد تکرار").setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ ->
                selectedRepeatCount = (input.text.toString().toIntOrNull() ?: 3).coerceIn(1, 100)
                selectedRepeatMode = "count"
                chooseRingtone()
            }.show()
    }

    private fun chooseRingtone() {
        val options = arrayOf("زنگ پیش‌فرض", "انتخاب زنگ از گوشی", "بدون صدا")
        AlertDialog.Builder(this).setTitle("صدای یادآور")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { selectedRingtoneUri = ""; updateReminderSummary() }
                    1 -> {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای یادآور")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            if (selectedRingtoneUri.isNotBlank() && selectedRingtoneUri != "silent") {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(selectedRingtoneUri))
                            }
                        }
                        ringtonePickerLauncher.launch(intent)
                    }
                    2 -> { selectedRingtoneUri = "silent"; updateReminderSummary() }
                }
            }.show()
    }

    private fun updateReminderSummary() {
        if (!::reminderSummary.isInitialized) return
        reminderSummary.text = if (selectedReminderEnabled && selectedReminderAt > 0) {
            val date = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(selectedReminderAt)
            val repeat = when (selectedRepeatMode) {
                "daily" -> "روزانه"; "weekly" -> "هفتگی"; "monthly" -> "ماهانه"
                "interval" -> "هر $selectedRepeatInterval دقیقه"; "count" -> "$selectedRepeatCount بار"
                else -> "بدون تکرار"
            }
            val sound = when {
                selectedRingtoneUri.isBlank() -> "پیش‌فرض"
                selectedRingtoneUri == "silent" -> "بی‌صدا"
                else -> "دلخواه ✓"
            }
            "یادآور: $date | تکرار: $repeat | صدا: $sound"
        } else REMINDER_NONE_TEXT
    }

    private fun scheduleReminder(note: Note) {
        if (!note.reminderEnabled || note.reminderAt <= 0) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("note_id", note.id); putExtra("note_title", note.title)
            putExtra("note_body", note.body); putExtra("repeat_mode", note.repeatMode)
            putExtra("repeat_count", note.repeatCount); putExtra("repeat_interval", note.repeatIntervalMinutes)
            putExtra("fired_count", note.reminderFiredCount); putExtra("ringtone_uri", note.ringtoneUri)
        }
        val pendingIntent = PendingIntent.getBroadcast(this, note.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.reminderAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.reminderAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.reminderAt, pendingIntent)
        }
    }

    private fun cancelReminder(noteId: String) {
        val intent = Intent(this, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, noteId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    // ===================================================================
    // کانال‌های اعلان
    // ===================================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID, "یادآورها", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "اعلان‌های یادآور"; enableVibration(true)
            })
            manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID_SILENT, "صدای دلخواه", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "یادآور با پخش دستی"; setSound(null, null); enableVibration(true)
            })
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
        }
    }

    // ===================================================================
    // سیستم جدید گفتار (یکپارچه + آنلاین/آفلاین)
    // ===================================================================
    private fun toggleVoiceInput(offline: Boolean) {
        if (continuousSpeech) {
            stopContinuousSpeech()
        } else {
            isListening = false
            speechRestartRequested = false
            transientErrorCount = 0
            isOfflineMode = offline
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTarget = lastFocusedInput
            pendingVoiceContinuous = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE)
            Toast.makeText(this, "مجوز میکروفون را بده؛ ضبط بلافاصله شروع می‌شود.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "تشخیص گفتار در دسترس نیست.", Toast.LENGTH_LONG).show()
            return
        }

        val target = lastFocusedInput ?: bodyInput
        speechTarget = target
        continuousSpeech = true
        speechRestartRequested = true
        voiceBaseText = target.text.toString()
        
        speechRecognizer?.destroy()
        
        // انتخاب موتور بر اساس حالت آنلاین یا آفلاین
        speechRecognizer = if (isOfflineMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread { statusText.text = if (isOfflineMode) "گوش می‌دهم (آفلاین)..." else "گوش می‌دهم (آنلاین)..." }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            // 🔧 تایپ همزمان (Real-time)
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) {
                    runOnUiThread {
                        val target = speechTarget
                        if (target != null) {
                            val separator = if (target == taskInput && voiceBaseText.isNotBlank() && !voiceBaseText.endsWith("\n")) "\n" else " "
                            val newText = if (voiceBaseText.isBlank()) partial else "$voiceBaseText$separator$partial"
                            target.setText(newText)
                            target.setSelection(target.text.length)
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                transientErrorCount = 0
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                
                if (spoken.isNotBlank()) {
                    runOnUiThread {
                        val target = speechTarget
                        if (target != null) {
                            val separator = if (target == taskInput && voiceBaseText.isNotBlank() && !voiceBaseText.endsWith("\n")) "\n" else " "
                            val finalText = if (voiceBaseText.isBlank()) spoken else "$voiceBaseText$separator$spoken"
                            target.setText(finalText)
                            target.setSelection(target.text.length)
                            voiceBaseText = finalText // آپدیت برای جمله بعدی
                        }
                    }
                }

                if (continuousSpeech && speechRestartRequested) {
                    statusText.postDelayed({
                        if (continuousSpeech && speechRestartRequested) {
                            try { speechRecognizer?.cancel() } catch (_: Exception) {}
                            launchRecognizer()
                        }
                    }, 500)
                } else {
                    if (::statusText.isInitialized) statusText.text = "تشخیص گفتار پایان یافت."
                }
            }

            override fun onError(error: Int) {
                isListening = false
                // 🔧 مدیریت خطای پشتیبانی نشدن زبان در حالت آفلاین
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    if (isOfflineMode) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "زبان فارسی آفلاین نیست. اتصال به آنلاین...", Toast.LENGTH_SHORT).show() }
                        isOfflineMode = false
                        speechRecognizer?.destroy()
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                        // ست کردن مجدد listener در کد اصلی انجام شده
                        startVoiceInput() 
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, "خطای زبان در سیستم شما.", Toast.LENGTH_LONG).show() }
                        stopContinuousSpeech()
                    }
                    return
                }

                if (!continuousSpeech || !speechRestartRequested) {
                    if (::statusText.isInitialized) statusText.text = "تشخیص گفتار پایان یافت."
                    return
                }

                val silentRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val busyRecoverable = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                
                if (silentRecoverable) transientErrorCount = 0
                else {
                    transientErrorCount++
                    if (transientErrorCount > 40) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "گفتار پس از چند تلاش متوقف شد.", Toast.LENGTH_SHORT).show() }
                        stopContinuousSpeech()
                        return
                    }
                }

                val delay = when {
                    silentRecoverable -> 300L
                    busyRecoverable -> 1500L
                    else -> 800L
                }

                statusText.postDelayed({
                    if (continuousSpeech && speechRestartRequested) {
                        try { speechRecognizer?.cancel() } catch (_: Exception) {}
                        launchRecognizer()
                    }
                }, delay)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        launchRecognizer()
    }

    private fun launchRecognizer() {
        if (isListening) return
        val recognizer = speechRecognizer ?: return
        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isListening = false
            if (continuousSpeech && speechRestartRequested) {
                statusText.postDelayed({ if (continuousSpeech && speechRestartRequested) launchRecognizer() }, 800)
            }
        }
    }

    private fun stopContinuousSpeech() {
        continuousSpeech = false
        speechRestartRequested = false
        isListening = false
        transientErrorCount = 0
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        speechRecognizer = null
        if (::statusText.isInitialized) statusText.text = "گفتار متوقف شد."
    }

    // ===================================================================
    // ذخیره/بازیابی JSON و PIN
    // ===================================================================
    private fun saveNotes() {
        try {
            val array = JSONArray()
            notes.forEach { note ->
                array.put(JSONObject().apply {
                    put("id", note.id); put("title", note.title); put("body", note.body)
                    put("category", note.category); put("private", note.isPrivate)
                    val tasksArray = JSONArray()
                    note.tasks.forEach { tasksArray.put(JSONObject().apply { put("id", it.id); put("text", it.text); put("done", it.done) }) }
                    put("tasks", tasksArray)
                    put("reminderAt", note.reminderAt); put("reminderEnabled", note.reminderEnabled)
                    put("repeatMode", note.repeatMode); put("repeatCount", note.repeatCount)
                    put("repeatIntervalMinutes", note.repeatIntervalMinutes); put("ringtoneUri", note.ringtoneUri)
                    put("reminderFiredCount", note.reminderFiredCount)
                })
            }
            securePrefs.edit().putString(NOTES_KEY, array.toString()).apply()
        } catch (e: Exception) { Log.e(TAG, "Saving failed", e) }
    }

    private fun loadNotes() {
        notes.clear()
        val raw = securePrefs.getString(NOTES_KEY, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val tasks = mutableListOf<Task>()
                val tasksArray = obj.optJSONArray("tasks") ?: JSONArray()
                for (j in 0 until tasksArray.length()) {
                    val tObj = tasksArray.optJSONObject(j) ?: continue
                    tasks.add(Task(id = tObj.optString("id").ifBlank { UUID.randomUUID().toString() }, text = tObj.optString("text"), done = tObj.optBoolean("done", false)))
                }
                notes.add(Note(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = obj.optString("title"), body = obj.optString("body"),
                    category = obj.optString("category", "عمومی"), isPrivate = obj.optBoolean("private", false),
                    tasks = tasks, reminderAt = obj.optLong("reminderAt", 0L), reminderEnabled = obj.optBoolean("reminderEnabled", false),
                    repeatMode = obj.optString("repeatMode", "none"), repeatCount = obj.optInt("repeatCount", 0),
                    repeatIntervalMinutes = obj.optInt("repeatIntervalMinutes", 60), ringtoneUri = obj.optString("ringtoneUri", ""),
                    reminderFiredCount = obj.optInt("reminderFiredCount", 0)
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Loading failed", e) }
    }

    private fun showSetPinDialog(onSuccess: () -> Unit) {
        val input = EditText(this).apply { hint = "PIN جدید (حداقل ۴ رقم)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("ساخت PIN").setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) { Toast.makeText(this, "PIN باید حداقل ۴ رقم باشد.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                securePrefs.edit().putString(PIN_KEY, hashPin(pin)).apply()
                onSuccess()
            }.show()
    }

    private fun requestPin(message: String, callback: (Boolean) -> Unit) {
        val input = EditText(this).apply { hint = "PIN"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle(message).setView(input)
            .setNegativeButton("لغو") { _, _ -> callback(false) }
            .setPositiveButton("تأیید") { _, _ ->
                val valid = securePrefs.getString(PIN_KEY, null) == hashPin(input.text.toString())
                if (!valid) Toast.makeText(this, "PIN اشتباه است.", Toast.LENGTH_SHORT).show()
                callback(valid)
            }.show()
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val target = pendingVoiceTarget ?: lastFocusedInput
            if (target != null) {
                target.postDelayed({ startVoiceInput() }, 100)
            }
        }
    }

    override fun onDestroy() {
        stopContinuousSpeech()
        super.onDestroy()
    }
}

// =====================================================================
// BroadcastReceiver
// =====================================================================
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra("note_id") ?: return
        val title = intent.getStringExtra("note_title") ?: "یادآور یادینو"
        val body = intent.getStringExtra("note_body").orEmpty()
        val repeatMode = intent.getStringExtra("repeat_mode") ?: "none"
        val repeatCount = intent.getIntExtra("repeat_count", 0)
        val interval = intent.getIntExtra("repeat_interval", 60).coerceAtLeast(1)
        val firedCount = intent.getIntExtra("fired_count", 0)
        val ringtone = intent.getStringExtra("ringtone_uri").orEmpty()

        val isCustom = ringtone.isNotBlank() && ringtone != "silent"
        val isSilent = ringtone == "silent"
        val channelToUse = if (isCustom || isSilent) CHANNEL_ID_SILENT else CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelToUse)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.ifBlank { "یادآور یادینو" })
            .setContentText(body.ifBlank { "زمان یادآوری فرا رسیده است." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            when {
                isSilent -> builder.setSound(null)
                isCustom -> builder.setSound(Uri.parse(ringtone))
            }
        }

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pendingResult = goAsync()

        if (canNotify) {
            try { NotificationManagerCompat.from(context).notify(noteId.hashCode(), builder.build()) } catch (e: SecurityException) {}
        }

        if (isCustom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playCustomRingtone(context, Uri.parse(ringtone), pendingResult)
        } else pendingResult.finish()

        val nextTime = when (repeatMode) {
            "daily" -> System.currentTimeMillis() + 24L * 60L * 60L * 1000L
            "weekly" -> System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
            "monthly" -> Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis(); add(Calendar.MONTH, 1) }.timeInMillis
            "interval" -> System.currentTimeMillis() + interval * 60_000L
            "count" -> if (firedCount < repeatCount) System.currentTimeMillis() + interval * 60_000L else 0L
            else -> 0L
        }

        if (nextTime > System.currentTimeMillis()) {
            val nextIntent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("note_id", noteId); putExtra("note_title", title); putExtra("note_body", body)
                putExtra("repeat_mode", repeatMode); putExtra("repeat_count", repeatCount)
                putExtra("repeat_interval", interval); putExtra("fired_count", firedCount + 1)
                putExtra("ringtone_uri", ringtone)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, noteId.hashCode(), nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent)
                }
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent)
            }
        }
    }

    private fun playCustomRingtone(context: Context, uri: Uri, pendingResult: PendingResult) {
        try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (ringtone == null) { pendingResult.finish(); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = false
            ringtone.audioAttributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            ringtone.play()
            Handler(Looper.getMainLooper()).postDelayed({ try { ringtone.stop() } catch (_: Exception) {}; pendingResult.finish() }, 5000L)
        } catch (e: Exception) { pendingResult.finish() }
    }
}